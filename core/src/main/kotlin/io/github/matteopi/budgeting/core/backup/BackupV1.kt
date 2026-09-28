package io.github.matteopi.budgeting.core.backup

import kotlinx.serialization.Serializable

/** The whole JSON backup file, version 1. See docs/PLAN.md §5.17. */
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
    val settings: SettingsDto,
    val appSettings: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "budgeting-backup"
        const val CURRENT_VERSION = 1
    }
}
