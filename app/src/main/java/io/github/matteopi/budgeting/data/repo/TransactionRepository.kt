package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.widget.WidgetUpdater
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransactionRepository(
    private val database: BudgetDatabase,
    private val transactionDao: TransactionDao,
    private val clock: Clock,
    private val widgetUpdater: WidgetUpdater,
) {
    fun observeAll(): Flow<List<Transaction>> = transactionDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeRecent(limit: Int): Flow<List<Transaction>> =
        transactionDao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    suspend fun add(tx: Transaction): Long {
        val id = transactionDao.insert(tx.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    /** Inserts both legs of a transfer between two of the user's accounts, sharing a group id. */
    suspend fun addTransfer(fromAccountId: Long, toAccountId: Long, amount: Money, date: LocalDate, label: String) {
        val groupId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        val magnitude = amount.abs()
        database.withTransaction {
            transactionDao.insert(
                Transaction(
                    accountId = fromAccountId,
                    amount = -magnitude,
                    date = date,
                    createdAtEpochMillis = now,
                    categoryId = null,
                    label = label,
                    kind = TxKind.TRANSFER,
                    transferGroupId = groupId,
                ).toEntity(),
            )
            transactionDao.insert(
                Transaction(
                    accountId = toAccountId,
                    amount = magnitude,
                    date = date,
                    createdAtEpochMillis = now,
                    categoryId = null,
                    label = label,
                    kind = TxKind.TRANSFER,
                    transferGroupId = groupId,
                ).toEntity(),
            )
        }
        widgetUpdater.requestUpdate()
    }

    /** Updates [tx]; if it is one leg of a transfer, mirrors the amount/date onto the other leg. */
    suspend fun update(tx: Transaction) {
        database.withTransaction {
            transactionDao.update(tx.toEntity())
            val groupId = tx.transferGroupId
            if (tx.kind == TxKind.TRANSFER && groupId != null) {
                val other = transactionDao.getTransferGroup(groupId).map { it.toModel() }.firstOrNull { it.id != tx.id }
                if (other != null) {
                    transactionDao.update(other.copy(amount = -tx.amount, date = tx.date).toEntity())
                }
            }
        }
        widgetUpdater.requestUpdate()
    }

    /**
     * Turns the NORMAL transaction [edited] (the stored row with the user's edits applied) into one leg of a move
     * between accounts: kind TRANSFER, no category, a new group id. If [otherAccountId] names another account the
     * opposite leg (negated amount, same date and label) is created there too; null means the other account is
     * not in the app. All in one DB transaction. Returns the new group id.
     */
    suspend fun convertToTransfer(edited: Transaction, otherAccountId: Long?): String {
        require(edited.kind == TxKind.NORMAL) { "Only a normal transaction can become a transfer" }
        val groupId = UUID.randomUUID().toString()
        val legs = TransferEdits.toTransfer(edited, otherAccountId, groupId, clock.nowMillis())
        database.withTransaction {
            transactionDao.update(legs.first().toEntity())
            legs.drop(1).forEach { transactionDao.insert(it.toEntity()) }
        }
        widgetUpdater.requestUpdate()
        return groupId
    }

    /**
     * Edits the transfer that the row [legId] belongs to so that money leaves [fromAccountId] and arrives in
     * [toAccountId] (null = an account that is not in the app, so that side has no leg). Existing legs move to
     * their new account, a side that had no leg gets one, a side now null loses its leg; both legs get [amount]
     * (a magnitude), [date] and [label]. At least one account is required and they must differ. One DB
     * transaction ([TransferEdits.plan] decides the rows).
     */
    suspend fun updateTransfer(
        legId: Long,
        fromAccountId: Long?,
        toAccountId: Long?,
        amount: Money,
        date: LocalDate,
        label: String,
    ) {
        require(fromAccountId != null || toAccountId != null) { "A transfer needs at least one account in the app" }
        require(fromAccountId == null || fromAccountId != toAccountId) { "A transfer needs two different accounts" }
        val leg = transactionDao.get(legId)?.toModel() ?: return
        database.withTransaction {
            val groupId = leg.transferGroupId
            val legs = if (groupId != null) transactionDao.getTransferGroup(groupId).map { it.toModel() } else listOf(leg)
            val plan = TransferEdits.plan(
                legs = legs,
                fromAccountId = fromAccountId,
                toAccountId = toAccountId,
                amount = amount,
                date = date,
                label = label,
                groupId = groupId ?: UUID.randomUUID().toString(),
                nowMillis = clock.nowMillis(),
            )
            plan.deletes.forEach { transactionDao.delete(it) }
            plan.updates.forEach { transactionDao.update(it.toEntity()) }
            plan.inserts.forEach { transactionDao.insert(it.toEntity()) }
        }
        widgetUpdater.requestUpdate()
    }

    /**
     * "Not a transfer": the transfer leg [keepId] becomes a NORMAL transaction in [categoryId] (same account,
     * amount, date and label) and the other leg of its transfer, if it has one, is deleted. One DB transaction.
     */
    suspend fun convertToNormal(keepId: Long, categoryId: Long) {
        val keep = transactionDao.get(keepId)?.toModel() ?: return
        require(keep.kind == TxKind.TRANSFER) { "Only a transfer leg can stop being a transfer" }
        database.withTransaction {
            val groupId = keep.transferGroupId
            if (groupId != null) {
                transactionDao.getTransferGroup(groupId).filter { it.id != keepId }.forEach { transactionDao.delete(it.id) }
            }
            transactionDao.update(TransferEdits.toNormal(keep, categoryId).toEntity())
        }
        widgetUpdater.requestUpdate()
    }

    /**
     * How many NORMAL transactions other than [exceptId] share the merchant [merchantKey]
     * (`MerchantKey.normalise` of their label) and would change if they were put in [categoryId]: those not
     * already in it, whose direction the category allows, and that are not parts of a receipt.
     */
    suspend fun countRecategorisable(merchantKey: String, categoryId: Long, exceptId: Long): Int =
        recategorisable(merchantKey, categoryId, exceptId).size

    /**
     * Puts every NORMAL transaction other than [exceptId] that shares the merchant [merchantKey] into
     * [categoryId] (see [countRecategorisable] for which ones), and, when [rememberRule] is true, saves a
     * merchant rule so future statement imports use that category for the merchant. One DB transaction.
     * Returns how many transactions changed.
     */
    suspend fun recategorise(merchantKey: String, categoryId: Long, exceptId: Long, rememberRule: Boolean = false): Int {
        val changed = database.withTransaction {
            val targets = recategorisable(merchantKey, categoryId, exceptId)
            targets.forEach { transactionDao.update(it.copy(categoryId = categoryId).toEntity()) }
            if (rememberRule) saveMerchantRule(merchantKey, categoryId)
            targets.size
        }
        widgetUpdater.requestUpdate()
        return changed
    }

    /** Saves (or replaces) the merchant rule "[merchantKey] is [categoryId]" that statement imports consult. */
    suspend fun rememberMerchantRule(merchantKey: String, categoryId: Long) {
        saveMerchantRule(merchantKey, categoryId)
        widgetUpdater.requestUpdate()
    }

    private suspend fun saveMerchantRule(merchantKey: String, categoryId: Long) {
        if (merchantKey.isBlank()) return
        database.importDao().upsertMerchantRule(MerchantRule(merchantKey = merchantKey, categoryId = categoryId).toEntity())
    }

    private suspend fun recategorisable(merchantKey: String, categoryId: Long, exceptId: Long): List<Transaction> {
        if (merchantKey.isBlank()) return emptyList()
        val flow = database.categoryDao().get(categoryId)?.flow?.let { CategoryFlow.valueOf(it) } ?: return emptyList()
        return transactionDao.getNormal().map { it.toModel() }.filter { tx ->
            tx.id != exceptId &&
                tx.categoryId != categoryId &&
                tx.receiptId == null &&
                allowsDirection(flow, tx.amount) &&
                MerchantKey.normalise(tx.label) == merchantKey
        }
    }

    /** A category that is only for spending can't take money in, and one only for income can't take money out. */
    private fun allowsDirection(flow: CategoryFlow, amount: Money): Boolean = when (flow) {
        CategoryFlow.OUT -> !amount.isPositive
        CategoryFlow.IN -> !amount.isNegative
        CategoryFlow.BOTH -> true
    }

    /** Deletes [id]; if it is a transfer leg, both legs are deleted. */
    suspend fun delete(id: Long) {
        database.withTransaction {
            val entity = transactionDao.get(id)
            val groupId = entity?.transferGroupId
            if (entity != null && entity.kind == TxKind.TRANSFER.name && groupId != null) {
                transactionDao.deleteTransferGroup(groupId)
            } else {
                transactionDao.delete(id)
            }
        }
        widgetUpdater.requestUpdate()
    }

    /** Logs a one-tap preset as a NORMAL transaction today. */
    suspend fun logPreset(preset: Preset, defaultAccountId: Long?, today: LocalDate): Long {
        val accountId = preset.accountId ?: defaultAccountId ?: error("No account to log the preset against")
        val signed = if (preset.flow == CategoryFlow.OUT) -preset.amount.abs() else preset.amount.abs()
        val id = transactionDao.insert(
            Transaction(
                accountId = accountId,
                amount = signed,
                date = today,
                createdAtEpochMillis = clock.nowMillis(),
                categoryId = preset.categoryId,
                label = preset.label,
                kind = TxKind.NORMAL,
            ).toEntity(),
        )
        widgetUpdater.requestUpdate()
        return id
    }
}
