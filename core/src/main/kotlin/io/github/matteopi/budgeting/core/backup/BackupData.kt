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
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction

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
)
