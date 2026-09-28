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
