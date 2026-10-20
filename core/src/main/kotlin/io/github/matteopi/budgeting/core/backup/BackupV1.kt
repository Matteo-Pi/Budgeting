package io.github.matteopi.budgeting.core.backup

import kotlinx.serialization.Serializable

/**
 * The whole JSON backup file. The class keeps its version-1 name but holds versions 1 to 6:
 * version 2 adds the receipt tables and `TransactionDto.receiptId`, version 3 adds the eight food
 * tables (docs/PLAN.md §11.3), version 4 adds what the kitchen has (`ownedItems`) and two ingredient
 * fields (§11.11), version 5 the Planner's modules and to-do list (§13.5), version 6 its timetable and
 * calendar links, and an older file simply decodes with those left empty. See §5.17.
 */
@Serializable
data class BackupV1(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    val exportedAt: String,
    val accounts: List<AccountDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TransactionDto> = emptyList(),
    val recurringRules: List<RecurringRuleDto> = emptyList(),
    val scheduledItems: List<ScheduledItemDto> = emptyList(),
    val termPeriods: List<TermPeriodDto> = emptyList(),
    val predictions: List<PredictionDto> = emptyList(),
    val sinkingFunds: List<SinkingFundDto> = emptyList(),
    val scenarios: List<ScenarioDto> = emptyList(),
    val presets: List<PresetDto> = emptyList(),
    val merchantRules: List<MerchantRuleDto> = emptyList(),
    val reconciliations: List<ReconciliationDto> = emptyList(),
    val importBatches: List<ImportBatchDto> = emptyList(),
    val balanceSnapshots: List<BalanceSnapshotDto> = emptyList(),
    val categoryWeekSnapshots: List<CategoryWeekSnapshotDto> = emptyList(),
    /** Version 2. */
    val receipts: List<ReceiptDto> = emptyList(),
    val receiptItems: List<ReceiptItemDto> = emptyList(),
    val products: List<ProductDto> = emptyList(),
    val productAliases: List<ProductAliasDto> = emptyList(),
    /** Version 3: ingredients, recipes and the shopping list. */
    val ingredients: List<IngredientDto> = emptyList(),
    val ingredientPortions: List<IngredientPortionDto> = emptyList(),
    val ingredientAliases: List<IngredientAliasDto> = emptyList(),
    val recipes: List<RecipeDto> = emptyList(),
    val recipeLines: List<RecipeLineDto> = emptyList(),
    val recipeSteps: List<RecipeStepDto> = emptyList(),
    val shoppingEntries: List<ShoppingEntryDto> = emptyList(),
    val shoppingMarks: List<ShoppingMarkDto> = emptyList(),
    /** Version 4: what the kitchen has. */
    val ownedItems: List<OwnedItemDto> = emptyList(),
    /** Version 5: the Planner's modules, tasks, checklists and Done log. */
    val plannerModules: List<PlannerModuleDto> = emptyList(),
    val plannerTasks: List<PlannerTaskDto> = emptyList(),
    val plannerChecklist: List<PlannerChecklistItemDto> = emptyList(),
    val plannerCompletions: List<PlannerCompletionDto> = emptyList(),
    /** Version 6: the timetable, its one-date changes, and the events written to a calendar. */
    val plannerEntries: List<PlannerEntryDto> = emptyList(),
    val plannerEntryExceptions: List<PlannerEntryExceptionDto> = emptyList(),
    val plannerCalendarLinks: List<PlannerCalendarLinkDto> = emptyList(),
    val settings: SettingsDto,
    val appSettings: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "budgeting-backup"
        const val CURRENT_VERSION = 6
    }
}
