package io.github.matteopi.budgeting.core.backup

import kotlinx.serialization.Serializable

/*
 * Serializable DTOs for JSON backup/restore. Primitives only: ISO date strings ("2026-09-28"),
 * pence as Long, enum names as String. Ids are preserved as-is. See docs/PLAN.md §5.17.
 */

@Serializable
data class AccountDto(
    val id: Long,
    val name: String,
    val type: String,
    val overdraftLimitPence: Long,
    val includeInSafeToSpend: Boolean,
    val archived: Boolean,
    val sortOrder: Int,
)

@Serializable
data class CategoryDto(
    val id: Long,
    val name: String,
    val flow: String,
    val forecastMode: String,
    val colorArgb: Long?,
    val isSystem: Boolean,
    val systemKey: String?,
    val isMajorIncomeDefault: Boolean,
    val archived: Boolean,
    val sortOrder: Int,
)

@Serializable
data class TransactionDto(
    val id: Long,
    val accountId: Long,
    val amountPence: Long,
    val date: String,
    val createdAtEpochMillis: Long,
    val categoryId: Long?,
    val label: String,
    val note: String,
    val kind: String,
    val transferGroupId: String?,
    val recurringRuleId: Long?,
    val scheduledItemId: Long?,
    val importBatchId: Long?,
    val importKey: String?,
    /** Added in backup version 2; absent in version-1 files. */
    val receiptId: Long? = null,
)

@Serializable
data class RecurringRuleDto(
    val id: Long,
    val label: String,
    val accountId: Long,
    val categoryId: Long?,
    val amountPence: Long,
    val frequency: String,
    val interval: Int,
    val startDate: String,
    val endDate: String?,
    val postingMode: String,
    val postedThrough: String?,
    val toAccountId: Long?,
    val isMajorIncome: Boolean,
    val active: Boolean,
)

@Serializable
data class ScheduledItemDto(
    val id: Long,
    val label: String,
    val accountId: Long,
    val categoryId: Long?,
    val amountPence: Long,
    val date: String,
    val postingMode: String,
    val toAccountId: Long?,
    val isMajorIncome: Boolean,
    val posted: Boolean,
    val skipped: Boolean,
)

@Serializable
data class TermPeriodDto(
    val id: Long,
    val name: String,
    val startDate: String,
    val endDate: String,
)

@Serializable
data class PredictionDto(
    val id: Long,
    val categoryId: Long,
    val regime: String,
    val mode: String,
    val weeklyAmountPence: Long,
    val adjustPercent: Int,
)

@Serializable
data class SinkingFundDto(
    val id: Long,
    val name: String,
    val targetPence: Long,
    val startDate: String,
    val targetDate: String,
    val categoryId: Long?,
    val status: String,
)

@Serializable
data class ScenarioDto(
    val id: Long,
    val name: String,
    val items: List<ScenarioItemDto>,
)

@Serializable
data class ScenarioItemDto(
    val id: Long,
    val scenarioId: Long,
    val type: String,
    val label: String,
    val accountId: Long?,
    val categoryId: Long?,
    val amountPence: Long,
    val date: String?,
    val frequency: String?,
    val interval: Int,
    val endDate: String?,
    val percent: Int,
    val regime: String?,
)

@Serializable
data class PresetDto(
    val id: Long,
    val label: String,
    val amountPence: Long,
    val flow: String,
    val categoryId: Long,
    val accountId: Long?,
    val showOnWidget: Boolean,
    val sortOrder: Int,
)

@Serializable
data class MerchantRuleDto(
    val id: Long,
    val merchantKey: String,
    val categoryId: Long,
    val label: String?,
)

@Serializable
data class ReconciliationDto(
    val id: Long,
    val accountId: Long,
    val date: String,
    val expectedPence: Long,
    val actualPence: Long,
    val transactionId: Long?,
    val periodStart: String,
)

@Serializable
data class ImportBatchDto(
    val id: Long,
    val accountId: Long,
    val importedAtEpochMillis: Long,
    val fileName: String,
    val rowCount: Int,
    val firstDate: String?,
    val lastDate: String?,
)

@Serializable
data class BalanceSnapshotDto(
    val id: Long,
    val createdOn: String,
    val targetDate: String,
    val tier: Int,
    val p10Pence: Long?,
    val p50Pence: Long,
    val p90Pence: Long?,
)

@Serializable
data class CategoryWeekSnapshotDto(
    val id: Long,
    val createdOn: String,
    val weekStart: String,
    val categoryId: Long,
    val tier: Int,
    val predictedPence: Long,
)

@Serializable
data class SettingsDto(
    val zeroPoint: String,
    val bufferPence: Long,
    val defaultAccountId: Long?,
    val historyStartDate: String?,
    val simulations: Int,
    val forecastHorizonDays: Int,
)

@Serializable
data class ReceiptDto(
    val id: Long,
    val store: String,
    val date: String,
    val totalPence: Long,
    val source: String,
    val transactionId: Long?,
    val createdAtEpochMillis: Long,
)

@Serializable
data class ReceiptItemDto(
    val id: Long,
    val receiptId: Long,
    val productId: Long?,
    val rawName: String,
    val quantity: Int,
    val weightGrams: Int?,
    val unitPricePence: Long?,
    val pricePence: Long,
    val discountPence: Long,
    val categoryId: Long?,
)

@Serializable
data class ProductDto(
    val id: Long,
    val name: String,
    val categoryId: Long?,
)

@Serializable
data class ProductAliasDto(
    val id: Long,
    val store: String,
    val rawNameKey: String,
    val productId: Long,
)

// ---- Version 3: food (docs/PLAN.md §11.3) -----------------------------------------------------

@Serializable
data class IngredientDto(
    val id: Long,
    val name: String,
    val unit: String,
    val packSize: Double,
    val packLabel: String,
    val pricePence: Long?,
    val priceDate: String?,
    val store: String?,
    val productId: Long?,
    val productName: String?,
    val sourceUrl: String?,
    val barcode: String?,
    val kcal: Double?,
    val fat: Double?,
    val saturates: Double?,
    val carbs: Double?,
    val sugars: Double?,
    val fibre: Double?,
    val protein: Double?,
    val salt: Double?,
    val nutritionSource: String,
    val vegKind: String,
    val shelf: String,
    val note: String,
    val archived: Boolean,
    val createdAtEpochMillis: Long,
    /** Version 4 (docs/PLAN.md §11.11); absent in older files. */
    val addedSugars: Double? = null,
    /** Version 4: a `FoodGroup` name, or null to guess it from the name. */
    val foodGroup: String? = null,
)

@Serializable
data class IngredientPortionDto(
    val ingredientId: Long,
    val name: String,
    val amount: Double,
)

@Serializable
data class IngredientAliasDto(
    val id: Long,
    val nameKey: String,
    val ingredientId: Long,
)

@Serializable
data class RecipeDto(
    val id: Long,
    val title: String,
    val shortTitle: String?,
    val tagline: String?,
    val style: String?,
    val serves: Int,
    val prepMins: Int?,
    val cookMins: Int?,
    val timeNote: String?,
    val servedWith: String?,
    val tags: List<String>,
    val rating: Int,
    val notes: String,
    val source: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val archived: Boolean,
)

/** [position] orders the lines of one recipe. */
@Serializable
data class RecipeLineDto(
    val recipeId: Long,
    val position: Int,
    val group: String?,
    val text: String,
    val ingredientId: Long?,
    val quantity: Double?,
    val optional: Boolean,
)

/** [position] orders the steps, tips, equipment and storage notes of one recipe together. */
@Serializable
data class RecipeStepDto(
    val recipeId: Long,
    val position: Int,
    val kind: String,
    val title: String?,
    val time: String?,
    val body: String,
)

@Serializable
data class ShoppingEntryDto(
    val id: Long,
    val recipeId: Long?,
    val servings: Int?,
    val ingredientId: Long?,
    val quantity: Double?,
    val freeText: String?,
    val checked: Boolean,
    val addedAtEpochMillis: Long,
)

@Serializable
data class ShoppingMarkDto(
    val ingredientId: Long,
    val checked: Boolean,
    val haveIt: Boolean,
)

// ---- Version 4: what the kitchen has (docs/PLAN.md §11.11) ------------------------------------

@Serializable
data class OwnedItemDto(
    val ingredientId: Long,
    /** In the ingredient's base unit; null for "some, not measured". */
    val quantity: Double?,
    val updatedAtEpochMillis: Long,
)
