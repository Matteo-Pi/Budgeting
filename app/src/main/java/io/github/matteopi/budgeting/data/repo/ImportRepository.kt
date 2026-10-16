package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.importer.ImportChanges
import io.github.matteopi.budgeting.core.importer.ImportReconcile
import io.github.matteopi.budgeting.core.importer.OpeningBalanceAdjuster
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.reconcile.Reconcile
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.ImportDao
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.widget.WidgetUpdater
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A statement row that is money moving between two of the user's own accounts rather than spending.
 * [otherAccountId] is the account at the other end, or null when it is an account that isn't in the app
 * (the row then becomes a single transfer leg).
 */
data class ImportTransfer(val otherAccountId: Long?)

/**
 * One statement row the import wizard has decided to bring in: [row] is the parsed statement
 * row, [importKey] its dedupe key (from
 * [io.github.matteopi.budgeting.core.importer.ImportDedupe.importKeys]), [categoryId] the
 * category chosen for its merchant group (null = leave uncategorised), [transfer] set when the row
 * is a move between the user's accounts (then it has no category).
 */
data class SelectedImportRow(
    val row: ParsedRow,
    val importKey: String,
    val categoryId: Long?,
    val transfer: ImportTransfer? = null,
)

/**
 * What an import did: the [batch] of new transactions (null when no row was imported and so no batch was made),
 * and the [recordedDifference] saved as a balance update when the user chose to (null when none was needed).
 */
data class ImportOutcome(val batch: ImportBatch?, val recordedDifference: Money?)

/** Merchant rules (remembered category choices from statement imports) and import batches. */
class ImportRepository(
    private val database: BudgetDatabase,
    private val importDao: ImportDao,
    private val transactionDao: TransactionDao,
    private val widgetUpdater: WidgetUpdater,
) {
    fun observeMerchantRules(): Flow<List<MerchantRule>> = importDao.observeMerchantRules().map { list -> list.map { it.toModel() } }

    suspend fun addMerchantRule(rule: MerchantRule): Long {
        val id = importDao.insertMerchantRule(rule.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    suspend fun deleteMerchantRule(id: Long) {
        importDao.deleteMerchantRule(id)
        widgetUpdater.requestUpdate()
    }

    fun observeBatches(): Flow<List<ImportBatch>> = importDao.observeBatches().map { list -> list.map { it.toModel() } }

    /**
     * Creates the import batch and one transaction per row in [rows] (with `importKey` and the new
     * batch's id): NORMAL, or for a row with a [SelectedImportRow.transfer] a TRANSFER leg in a new
     * transfer group, plus the opposite leg on the other account when one was chosen (see
     * [ImportTransactions]). It remembers [rulesToRemember] (upserting by merchant key) and moves the
     * account's opening balance so rows dated before it don't double count (see
     * [rebuildOpeningBalanceMove]). All in one DB transaction (docs/PLAN.md §5.16, §6.5), so
     * [undoBatch] removes everything the batch created.
     */
    suspend fun importRows(
        accountId: Long,
        fileName: String,
        rows: List<SelectedImportRow>,
        rulesToRemember: List<MerchantRule>,
        nowMillis: Long,
    ): ImportBatch = requireNotNull(
        applyImport(accountId, fileName, rows, rulesToRemember, ImportChanges.NONE, nowMillis, batchWhenNoRows = true).batch,
    )

    /**
     * [importRows] together with what the user answered in the import wizard's check step, in one DB transaction
     * (docs/PLAN.md §5.16, §6.5): the new rows are inserted, then [changes] are applied to transactions that were
     * already in the app (updated: date, amount, import key; deleted, a transfer with both legs; moved to another
     * account), then the opening-balance move, then the reconciliation when [ImportChanges.reconcile] is set: a
     * [Reconcile.plan] for its date with the statement's balance as the actual one, whose RECONCILIATION
     * transaction is tagged with the batch (so [undoBatch] removes it and its reconciliation record). Only what
     * the batch created is undone; the changes to existing transactions stay. With no rows no batch is made.
     */
    suspend fun importWithChanges(
        accountId: Long,
        fileName: String,
        rows: List<SelectedImportRow>,
        rulesToRemember: List<MerchantRule>,
        changes: ImportChanges,
        nowMillis: Long,
    ): ImportOutcome = applyImport(accountId, fileName, rows, rulesToRemember, changes, nowMillis, batchWhenNoRows = false)

    private suspend fun applyImport(
        accountId: Long,
        fileName: String,
        rows: List<SelectedImportRow>,
        rulesToRemember: List<MerchantRule>,
        changes: ImportChanges,
        nowMillis: Long,
        batchWhenNoRows: Boolean,
    ): ImportOutcome {
        val outcome = database.withTransaction {
            val batch = if (rows.isNotEmpty() || batchWhenNoRows) {
                val draft = ImportBatch(
                    accountId = accountId,
                    importedAtEpochMillis = nowMillis,
                    fileName = fileName,
                    rowCount = rows.size,
                    firstDate = rows.minOfOrNull { it.row.date },
                    lastDate = rows.maxOfOrNull { it.row.date },
                )
                draft.copy(id = importDao.insertBatch(draft.toEntity()))
            } else {
                null
            }

            val openingDates = mutableMapOf<Long, LocalDate?>()
            for (selected in rows) {
                val otherId = selected.transfer?.otherAccountId?.takeIf { it != accountId }
                if (otherId != null && otherId !in openingDates) openingDates[otherId] = originalOpeningDate(otherId)
                val legs = ImportTransactions.forRow(
                    accountId = accountId,
                    selected = selected,
                    batchId = requireNotNull(batch).id,
                    nowMillis = nowMillis,
                    transferGroupId = UUID.randomUUID().toString(),
                    otherAccountOpeningDate = otherId?.let { openingDates[it] },
                )
                for (leg in legs) transactionDao.insert(leg.toEntity())
            }

            for (rule in rulesToRemember) {
                importDao.upsertMerchantRule(rule.toEntity())
            }

            applyToExisting(changes)

            // The accounts whose imported rows may have changed: the one imported into, and any an entry was moved to.
            val rebuild = linkedSetOf(accountId)
            changes.moves.forEach { rebuild += it.toAccountId }
            rebuild.forEach { rebuildOpeningBalanceMove(it, nowMillis) }

            val recorded = changes.reconcile?.let { recordDifference(accountId, it, batch?.id, nowMillis) }
            ImportOutcome(batch, recorded)
        }
        widgetUpdater.requestUpdate()
        return outcome
    }

    /** Updates, deletions and moves of transactions that were already in the app. Must be called inside a DB transaction. */
    private suspend fun applyToExisting(changes: ImportChanges) {
        for (update in changes.updates) {
            val current = transactionDao.get(update.id)?.toModel() ?: continue
            transactionDao.update(
                current.copy(
                    date = update.date ?: current.date,
                    amount = update.amount ?: current.amount,
                    importKey = update.importKey ?: current.importKey,
                ).toEntity(),
            )
        }
        for (id in changes.deletions) {
            val entity = transactionDao.get(id) ?: continue
            val group = entity.transferGroupId
            if (entity.kind == TxKind.TRANSFER.name && group != null) {
                transactionDao.deleteTransferGroup(group)
            } else {
                transactionDao.delete(id)
            }
        }
        for (move in changes.moves) {
            val current = transactionDao.get(move.id)?.toModel() ?: continue
            transactionDao.update(current.copy(accountId = move.toAccountId).toEntity())
        }
    }

    /**
     * Saves a reconciliation of [accountId] for `reconcile.date` so that the balance on that day is the statement's:
     * the plan "Update balance" makes ([Reconcile.plan]), dated on the statement's last day rather than today.
     * Returns the difference recorded, or null if the balance already matched. Must be called inside a DB
     * transaction, after everything else the import does.
     */
    private suspend fun recordDifference(accountId: Long, reconcile: ImportReconcile, batchId: Long?, nowMillis: Long): Money? {
        val onAccount = transactionDao.getForAccount(accountId).map { it.toModel() }
        val expected = Balances.accountBalance(accountId, onAccount, reconcile.date)
        if (expected == reconcile.statementBalance) return null

        val reconciliationDao = database.reconciliationDao()
        val untrackedCategoryId = database.categoryDao().untrackedCategoryId()
            ?: error("Untracked category is missing; ensureSeeded() must run before a statement is imported")
        val accountStartDate = onAccount.filter { it.kind == TxKind.OPENING_BALANCE }.minByOrNull { it.date }?.date
            ?: onAccount.minByOrNull { it.date }?.date
        val plan = Reconcile.plan(
            accountId = accountId,
            expected = expected,
            actual = reconcile.statementBalance,
            today = reconcile.date,
            previous = reconciliationDao.latestForAccount(accountId)?.toModel(),
            accountStartDate = accountStartDate,
            untrackedCategoryId = untrackedCategoryId,
            nowMillis = nowMillis,
        )
        val transactionId = plan.transaction?.let { transactionDao.insert(it.copy(importBatchId = batchId).toEntity()) }
        reconciliationDao.insert(plan.reconciliation.copy(transactionId = transactionId).toEntity())
        return plan.difference
    }

    /**
     * Deletes the batch's transactions (and the reconciliation records of any balance update it made) and the
     * batch itself, and re-derives the opening-balance move from the imports that remain, atomically. Batches
     * can be undone in any order. Changes the import made to transactions that were already in the app stay.
     */
    suspend fun undoBatch(id: Long, nowMillis: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val accountId = importDao.getBatch(id)?.accountId
            database.reconciliationDao().deleteForBatch(id)
            transactionDao.deleteForBatch(id)
            importDao.deleteBatch(id)
            if (accountId != null) rebuildOpeningBalanceMove(accountId, nowMillis)
        }
        widgetUpdater.requestUpdate()
    }

    /**
     * The account's opening balance already includes anything that happened before it, so
     * imported rows dated before it would double count. The fix ([OpeningBalanceAdjuster]) moves
     * the opening balance to the day before the earliest such row, reduced by their total. It is
     * recorded as two OPENING_BALANCE entries tagged with an import batch (one cancelling the
     * original, one adding the adjusted balance) so the original entry is never edited.
     *
     * The move is always re-derived from the original opening balance and whichever imported rows
     * remain, never stacked on a previous move, so it stays right however many imports are made
     * and whichever of them is undone. An existing pair is updated in place (keeping its ids and
     * batch), so undoing the latest import restores the previous entries exactly. Must be called
     * inside a DB transaction.
     */
    private suspend fun rebuildOpeningBalanceMove(accountId: Long, nowMillis: Long) {
        val onAccount = transactionDao.getForAccount(accountId).map { it.toModel() }
        val moves = onAccount
            .filter { it.kind == TxKind.OPENING_BALANCE && it.importBatchId != null }
            .sortedWith(compareBy({ it.date }, { it.id }))
        val original = onAccount
            .filter { it.kind == TxKind.OPENING_BALANCE && it.importBatchId == null }
            .minWithOrNull(compareBy({ it.date }, { it.id }))
        // Imported transfer legs count like imported spending: one dated before the opening balance is already in it.
        val imported = onAccount.filter { (it.kind == TxKind.NORMAL || it.kind == TxKind.TRANSFER) && it.importBatchId != null }
        val adjusted = original?.let { opening ->
            OpeningBalanceAdjuster.adjust(opening, imported.map { ParsedRow(it.date, it.amount, it.label, null, 0) })
        }
        if (original == null || adjusted == null) {
            moves.forEach { transactionDao.delete(it.id) }
            return
        }

        // The adjusted balance sits before the earliest imported row, so it sorts first.
        val wanted = listOf(adjusted.amount to adjusted.date, -original.amount to original.date)
        if (moves.size == wanted.size) {
            moves.zip(wanted).forEach { (move, target) ->
                val (amount, date) = target
                if (move.amount != amount || move.date != date) {
                    transactionDao.update(move.copy(amount = amount, date = date).toEntity())
                }
            }
            return
        }

        moves.forEach { transactionDao.delete(it.id) }
        val batchId = imported.filter { it.date.isBefore(original.date) }.minBy { it.date }.importBatchId
        for ((amount, date) in wanted) {
            transactionDao.insert(
                Transaction(
                    accountId = accountId,
                    amount = amount,
                    date = date,
                    createdAtEpochMillis = nowMillis,
                    categoryId = null,
                    label = MOVED_OPENING_BALANCE_LABEL,
                    kind = TxKind.OPENING_BALANCE,
                    importBatchId = batchId,
                ).toEntity(),
            )
        }
    }

    /** The date of the account's own opening balance (not the entries an import adds), or null if it has none. */
    private suspend fun originalOpeningDate(accountId: Long): LocalDate? =
        transactionDao.getForAccount(accountId).map { it.toModel() }
            .filter { it.kind == TxKind.OPENING_BALANCE && it.importBatchId == null }
            .minWithOrNull(compareBy({ it.date }, { it.id }))
            ?.date

    private companion object {
        const val MOVED_OPENING_BALANCE_LABEL = "Opening balance (moved by import)"
    }
}
