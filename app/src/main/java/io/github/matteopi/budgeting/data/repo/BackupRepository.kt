package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.backup.BackupData
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.AccountDao
import io.github.matteopi.budgeting.data.db.dao.CategoryDao
import io.github.matteopi.budgeting.data.db.dao.ImportDao
import io.github.matteopi.budgeting.data.db.dao.PlanDao
import io.github.matteopi.budgeting.data.db.dao.PresetDao
import io.github.matteopi.budgeting.data.db.dao.ReconciliationDao
import io.github.matteopi.budgeting.data.db.dao.ScenarioDao
import io.github.matteopi.budgeting.data.db.dao.SnapshotDao
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.first

/**
 * Reads/writes every table for backup export/restore (§6.10), as `core.backup.BackupData`. The
 * JSON encode/decode (`BackupMapper`/`BackupCodec`, from `core.backup`) is driven by
 * `backup/BackupViewModel`.
 */
class BackupRepository(
    private val database: BudgetDatabase,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val planDao: PlanDao,
    private val scenarioDao: ScenarioDao,
    private val presetDao: PresetDao,
    private val importDao: ImportDao,
    private val reconciliationDao: ReconciliationDao,
    private val snapshotDao: SnapshotDao,
    private val settingsStore: SettingsStore,
    private val widgetUpdater: WidgetUpdater,
) {
    suspend fun readAll(): BackupData {
        val scenarios = scenarioDao.observeScenarios().first()
        val scenarioItems = scenarioDao.observeScenarioItems().first().groupBy { it.scenarioId }
        return BackupData(
            accounts = accountDao.observeAll().first().map { it.toModel() },
            categories = categoryDao.observeAll().first().map { it.toModel() },
            transactions = transactionDao.observeAll().first().map { it.toModel() },
            recurringRules = planDao.observeRecurringRules().first().map { it.toModel() },
            scheduledItems = planDao.observeScheduledItems().first().map { it.toModel() },
            termPeriods = planDao.observeTermPeriods().first().map { it.toModel() },
            predictions = planDao.observePredictions().first().map { it.toModel() },
            sinkingFunds = planDao.observeSinkingFunds().first().map { it.toModel() },
            scenarios = scenarios.map { s -> s.toModel().copy(items = (scenarioItems[s.id] ?: emptyList()).map { it.toModel() }) },
            presets = presetDao.observeAll().first().map { it.toModel() },
            merchantRules = importDao.observeMerchantRules().first().map { it.toModel() },
            reconciliations = reconciliationDao.observeAll().first().map { it.toModel() },
            importBatches = importDao.observeBatches().first().map { it.toModel() },
            balanceSnapshots = snapshotDao.allBalanceSnapshots().map { it.toModel() },
            categoryWeekSnapshots = snapshotDao.allCategorySnapshots().map { it.toModel() },
            settings = settingsStore.budgetSettings.first(),
        )
    }

    /** Deletes every table (children first) then inserts [data] back with its original ids, atomically. */
    suspend fun replaceAll(data: BackupData) {
        database.withTransaction {
            snapshotDao.deleteAllCategorySnapshots()
            snapshotDao.deleteAllBalanceSnapshots()
            scenarioDao.deleteAllItems()
            scenarioDao.deleteAllScenarios()
            reconciliationDao.deleteAll()
            transactionDao.deleteAll()
            importDao.deleteAllBatches()
            importDao.deleteAllMerchantRules()
            presetDao.deleteAll()
            planDao.deleteAllPredictions()
            planDao.deleteAllSinkingFunds()
            planDao.deleteAllScheduledItems()
            planDao.deleteAllRecurringRules()
            planDao.deleteAllTermPeriods()
            categoryDao.deleteAll()
            accountDao.deleteAll()

            accountDao.insertAll(data.accounts.map { it.toEntity() })
            categoryDao.insertAll(data.categories.map { it.toEntity() })
            planDao.insertTermPeriods(data.termPeriods.map { it.toEntity() })
            planDao.insertRecurringRules(data.recurringRules.map { it.toEntity() })
            planDao.insertScheduledItems(data.scheduledItems.map { it.toEntity() })
            planDao.insertPredictions(data.predictions.map { it.toEntity() })
            planDao.insertSinkingFunds(data.sinkingFunds.map { it.toEntity() })
            // Batches before transactions: transactions.import_batch_id is an (immediately
            // checked) foreign key, so inserting them first fails for any imported row.
            importDao.insertBatches(data.importBatches.map { it.toEntity() })
            transactionDao.insertAll(data.transactions.map { it.toEntity() })
            presetDao.insertAll(data.presets.map { it.toEntity() })
            importDao.insertMerchantRules(data.merchantRules.map { it.toEntity() })
            reconciliationDao.insertAll(data.reconciliations.map { it.toEntity() })
            data.scenarios.forEach { scenario ->
                scenarioDao.insertScenario(scenario.toEntity(createdAt = System.currentTimeMillis()))
                scenarioDao.insertItems(scenario.items.map { it.toEntity() })
            }
            snapshotDao.insertBalanceSnapshots(data.balanceSnapshots.map { it.toEntity() })
            snapshotDao.insertCategorySnapshots(data.categoryWeekSnapshots.map { it.toEntity() })
        }
        settingsStore.replaceBudgetSettings(data.settings)
        widgetUpdater.requestUpdate()
    }
}
