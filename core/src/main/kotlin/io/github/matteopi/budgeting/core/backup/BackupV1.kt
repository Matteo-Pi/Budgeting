package io.github.matteopi.budgeting.core.backup

import kotlinx.serialization.Serializable

/**
 * The whole JSON backup file. The class keeps its version-1 name but holds versions 1 and 2:
 * version 2 adds the receipt tables and `TransactionDto.receiptId`, and a version-1 file simply
 * decodes with those left empty. See docs/PLAN.md §5.17.
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
    val settings: SettingsDto,
    val appSettings: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "budgeting-backup"
        const val CURRENT_VERSION = 2
    }
}
