package io.github.matteopi.budgeting.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.matteopi.budgeting.data.db.dao.AccountDao
import io.github.matteopi.budgeting.data.db.dao.CategoryDao
import io.github.matteopi.budgeting.data.db.dao.DayDao
import io.github.matteopi.budgeting.data.db.dao.FoodDao
import io.github.matteopi.budgeting.data.db.dao.ImportDao
import io.github.matteopi.budgeting.data.db.dao.PlanDao
import io.github.matteopi.budgeting.data.db.dao.PlannerDao
import io.github.matteopi.budgeting.data.db.dao.PresetDao
import io.github.matteopi.budgeting.data.db.dao.ReceiptDao
import io.github.matteopi.budgeting.data.db.dao.ReconciliationDao
import io.github.matteopi.budgeting.data.db.dao.ScenarioDao
import io.github.matteopi.budgeting.data.db.dao.SnapshotDao
import io.github.matteopi.budgeting.data.db.dao.TimetableDao
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.data.db.entities.AccountEntity
import io.github.matteopi.budgeting.data.db.entities.BalanceSnapshotEntity
import io.github.matteopi.budgeting.data.db.entities.CategoryEntity
import io.github.matteopi.budgeting.data.db.entities.CategoryWeekSnapshotEntity
import io.github.matteopi.budgeting.data.db.entities.ImportBatchEntity
import io.github.matteopi.budgeting.data.db.entities.IngredientAliasEntity
import io.github.matteopi.budgeting.data.db.entities.IngredientEntity
import io.github.matteopi.budgeting.data.db.entities.IngredientPortionEntity
import io.github.matteopi.budgeting.data.db.entities.MerchantRuleEntity
import io.github.matteopi.budgeting.data.db.entities.OwnedItemEntity
import io.github.matteopi.budgeting.data.db.entities.PredictionEntity
import io.github.matteopi.budgeting.data.db.entities.PresetEntity
import io.github.matteopi.budgeting.data.db.entities.ProductAliasEntity
import io.github.matteopi.budgeting.data.db.entities.ProductEntity
import io.github.matteopi.budgeting.data.db.entities.ReceiptEntity
import io.github.matteopi.budgeting.data.db.entities.ReceiptItemEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeLineEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeStepEntity
import io.github.matteopi.budgeting.data.db.entities.ReconciliationEntity
import io.github.matteopi.budgeting.data.db.entities.RecurringRuleEntity
import io.github.matteopi.budgeting.data.db.entities.ScenarioEntity
import io.github.matteopi.budgeting.data.db.entities.ScenarioItemEntity
import io.github.matteopi.budgeting.data.db.entities.ScheduledItemEntity
import io.github.matteopi.budgeting.data.db.entities.ShoppingEntryEntity
import io.github.matteopi.budgeting.data.db.entities.ShoppingMarkEntity
import io.github.matteopi.budgeting.data.db.entities.SinkingFundEntity
import io.github.matteopi.budgeting.data.db.entities.TermPeriodEntity
import io.github.matteopi.budgeting.data.db.entities.TransactionEntity
import io.github.matteopi.budgeting.data.db.entities.planner.CalendarLinkEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TimeCategoryEntity
import io.github.matteopi.budgeting.data.db.entities.planner.ChecklistItemEntity
import io.github.matteopi.budgeting.data.db.entities.planner.DayReviewEntity
import io.github.matteopi.budgeting.data.db.entities.planner.EntryExceptionEntity
import io.github.matteopi.budgeting.data.db.entities.planner.ModuleEntity
import io.github.matteopi.budgeting.data.db.entities.planner.PlanBlockEntity
import io.github.matteopi.budgeting.data.db.entities.planner.RecordEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TaskCompletionEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TaskEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TimetableEntryEntity

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
        ReceiptEntity::class,
        ReceiptItemEntity::class,
        ProductEntity::class,
        ProductAliasEntity::class,
        IngredientEntity::class,
        IngredientPortionEntity::class,
        IngredientAliasEntity::class,
        RecipeEntity::class,
        RecipeLineEntity::class,
        RecipeStepEntity::class,
        ShoppingEntryEntity::class,
        ShoppingMarkEntity::class,
        OwnedItemEntity::class,
        ModuleEntity::class,
        TaskEntity::class,
        ChecklistItemEntity::class,
        TaskCompletionEntity::class,
        TimetableEntryEntity::class,
        EntryExceptionEntity::class,
        CalendarLinkEntity::class,
        TimeCategoryEntity::class,
        PlanBlockEntity::class,
        RecordEntity::class,
        DayReviewEntity::class,
    ],
    version = 7,
    exportSchema = true,
    // Version 2 only adds tables and one nullable column (`transactions.receipt_id`); version 3 (the
    // Recipes section, docs/PLAN.md §11.3) only adds the eight food tables; version 4 (§11.11) only
    // adds `owned_items` and two nullable columns on `ingredients`; version 5 (the Planner's to-do
    // list, §13.5) only adds four `planner_` tables; version 6 (the timetable and calendar links) only
    // adds three more, and version 7 (the day record) four more. Room can migrate all six without a
    // spec. Never replace these with a destructive migration.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
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
    abstract fun receiptDao(): ReceiptDao
    abstract fun foodDao(): FoodDao
    abstract fun plannerDao(): PlannerDao
    abstract fun timetableDao(): TimetableDao
    abstract fun dayDao(): DayDao

    companion object {
        const val DATABASE_NAME = "budgeting.db"
    }
}
