package io.github.matteopi.budgeting.core.backup

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.model.ProductAliasRecord
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskCompletion
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientAliasRecord
import io.github.matteopi.budgeting.core.recipes.OwnedItem
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.ShoppingEntry
import io.github.matteopi.budgeting.core.recipes.ShoppingMark

/** Everything in a backup, as the app's own core models (no DTOs). */
data class BackupData(
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val recurringRules: List<RecurringRule> = emptyList(),
    val scheduledItems: List<ScheduledItem> = emptyList(),
    val termPeriods: List<TermPeriod> = emptyList(),
    val predictions: List<Prediction> = emptyList(),
    val sinkingFunds: List<SinkingFund> = emptyList(),
    val scenarios: List<Scenario> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val merchantRules: List<MerchantRule> = emptyList(),
    val reconciliations: List<Reconciliation> = emptyList(),
    val importBatches: List<ImportBatch> = emptyList(),
    val balanceSnapshots: List<BalanceSnapshot> = emptyList(),
    val categoryWeekSnapshots: List<CategoryWeekSnapshot> = emptyList(),
    val settings: BudgetSettings = BudgetSettings(),
    // Backup version 2. Declared last so positional construction of version-1 data keeps working.
    val receipts: List<Receipt> = emptyList(),
    val receiptItems: List<ReceiptItem> = emptyList(),
    val products: List<Product> = emptyList(),
    val productAliases: List<ProductAliasRecord> = emptyList(),
    // Backup version 3 (food). Declared last so positional construction of older data keeps working.
    /** Each with its portions. */
    val ingredients: List<Ingredient> = emptyList(),
    val ingredientAliases: List<IngredientAliasRecord> = emptyList(),
    /** Each with its lines and steps, in order. */
    val recipes: List<Recipe> = emptyList(),
    val shoppingEntries: List<ShoppingEntry> = emptyList(),
    val shoppingMarks: List<ShoppingMark> = emptyList(),
    // Backup version 4. Declared last so positional construction of older data keeps working.
    val ownedItems: List<OwnedItem> = emptyList(),
    // Backup version 5 (the Planner). Declared last so positional construction of older data keeps working.
    val plannerModules: List<Module> = emptyList(),
    /** Each with its checklist, in order. */
    val plannerTasks: List<Task> = emptyList(),
    val plannerCompletions: List<TaskCompletion> = emptyList(),
    // Backup version 6. Declared last so positional construction of older data keeps working.
    val plannerEntries: List<TimetableEntry> = emptyList(),
    val plannerEntryExceptions: List<EntryException> = emptyList(),
    val plannerCalendarLinks: List<CalendarLink> = emptyList(),
)
