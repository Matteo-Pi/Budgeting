package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.importer.OpeningBalanceAdjuster
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.ImportDao
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One statement row the import wizard has decided to bring in: [row] is the parsed statement
 * row, [importKey] its dedupe key (from
 * [io.github.matteopi.budgeting.core.importer.ImportDedupe.importKeys]), [categoryId] the
 * category chosen for its merchant group (null = leave uncategorised).
 */
data class SelectedImportRow(
    val row: ParsedRow,
    val importKey: String,
    val categoryId: Long?,
)

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
     * Creates the import batch and one NORMAL transaction per row in [rows] (with `importKey`
     * and the new batch's id), remembers [rulesToRemember] (upserting by merchant key), and — if
     * [openingBalanceTransaction] is given — applies [OpeningBalanceAdjuster] so rows dated
     * before it don't double-count. All in one DB transaction (docs/PLAN.md §5.16, §6.5).
     */
    suspend fun importRows(
        accountId: Long,
        fileName: String,
        rows: List<SelectedImportRow>,
        rulesToRemember: List<MerchantRule>,
        openingBalanceTransaction: Transaction?,
        nowMillis: Long,
    ): ImportBatch {
        var batch = ImportBatch(
            accountId = accountId,
            importedAtEpochMillis = nowMillis,
            fileName = fileName,
            rowCount = rows.size,
            firstDate = rows.minOfOrNull { it.row.date },
            lastDate = rows.maxOfOrNull { it.row.date },
        )
        database.withTransaction {
            val batchId = importDao.insertBatch(batch.toEntity())
            batch = batch.copy(id = batchId)

            for (selected in rows) {
                transactionDao.insert(
                    Transaction(
                        accountId = accountId,
                        amount = selected.row.amount,
                        date = selected.row.date,
                        createdAtEpochMillis = nowMillis,
                        categoryId = selected.categoryId,
                        label = selected.row.description,
                        kind = TxKind.NORMAL,
                        importBatchId = batchId,
                        importKey = selected.importKey,
                    ).toEntity(),
                )
            }

            for (rule in rulesToRemember) {
                importDao.upsertMerchantRule(rule.toEntity())
            }

            if (openingBalanceTransaction != null) {
                val adjusted = OpeningBalanceAdjuster.adjust(openingBalanceTransaction, rows.map { it.row })
                if (adjusted != null) {
                    transactionDao.update(adjusted.toEntity())
                }
            }
        }
        widgetUpdater.requestUpdate()
        return batch
    }

    /** Deletes the batch's transactions and the batch itself, atomically. */
    suspend fun undoBatch(id: Long) {
        database.withTransaction {
            transactionDao.deleteForBatch(id)
            importDao.deleteBatch(id)
        }
        widgetUpdater.requestUpdate()
    }
}
