package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.ImportDao
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Merchant rules (remembered category choices from statement imports) and import batches.
 *
 * The row-level import orchestration (`importRows`: parsing, dedupe, category suggestion and
 * the opening-balance adjustment) is deferred to WP-U5, which owns `ui/importer/` and the
 * `core.importer` APIs (CsvParser, StatementImport, ImportDedupe, CategorySuggester) that don't
 * exist in this worktree yet. This repository provides the CRUD + undo it will build on.
 */
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

    /** Deletes the batch's transactions and the batch itself, atomically. */
    suspend fun undoBatch(id: Long) {
        database.withTransaction {
            transactionDao.deleteForBatch(id)
            importDao.deleteBatch(id)
        }
        widgetUpdater.requestUpdate()
    }
}
