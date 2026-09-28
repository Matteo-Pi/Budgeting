package io.github.matteopi.budgeting.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.matteopi.budgeting.data.db.dao.AccountDao
import io.github.matteopi.budgeting.data.db.dao.CategoryDao
import io.github.matteopi.budgeting.data.db.dao.ImportDao
import io.github.matteopi.budgeting.data.db.dao.PlanDao
import io.github.matteopi.budgeting.data.db.dao.PresetDao
import io.github.matteopi.budgeting.data.db.dao.ReconciliationDao
import io.github.matteopi.budgeting.data.db.dao.ScenarioDao
import io.github.matteopi.budgeting.data.db.dao.SnapshotDao
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.data.db.entities.AccountEntity
import io.github.matteopi.budgeting.data.db.entities.BalanceSnapshotEntity
import io.github.matteopi.budgeting.data.db.entities.CategoryEntity
import io.github.matteopi.budgeting.data.db.entities.CategoryWeekSnapshotEntity
import io.github.matteopi.budgeting.data.db.entities.ImportBatchEntity
import io.github.matteopi.budgeting.data.db.entities.MerchantRuleEntity
import io.github.matteopi.budgeting.data.db.entities.PredictionEntity
import io.github.matteopi.budgeting.data.db.entities.PresetEntity
import io.github.matteopi.budgeting.data.db.entities.ReconciliationEntity
import io.github.matteopi.budgeting.data.db.entities.RecurringRuleEntity
import io.github.matteopi.budgeting.data.db.entities.ScenarioEntity
import io.github.matteopi.budgeting.data.db.entities.ScenarioItemEntity
import io.github.matteopi.budgeting.data.db.entities.ScheduledItemEntity
import io.github.matteopi.budgeting.data.db.entities.SinkingFundEntity
import io.github.matteopi.budgeting.data.db.entities.TermPeriodEntity
import io.github.matteopi.budgeting.data.db.entities.TransactionEntity

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        RecurringRuleEntity::class,
        ScheduledItemEntity::class,
        TermPeriodEntity::class,
        PredictionEntity::class,
        SinkingFundEntity::class,
        ScenarioEntity::class,
        ScenarioItemEntity::class,
        PresetEntity::class,
        MerchantRuleEntity::class,
        ReconciliationEntity::class,
        ImportBatchEntity::class,
        BalanceSnapshotEntity::class,
        CategoryWeekSnapshotEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BudgetDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun planDao(): PlanDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun presetDao(): PresetDao
    abstract fun importDao(): ImportDao
    abstract fun reconciliationDao(): ReconciliationDao
    abstract fun snapshotDao(): SnapshotDao

    companion object {
        const val DATABASE_NAME = "budgeting.db"
    }
}
