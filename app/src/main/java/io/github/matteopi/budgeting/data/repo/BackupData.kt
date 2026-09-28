package io.github.matteopi.budgeting.data.repo

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

/**
 * Everything a backup covers, as core models (Scenario carries its items already). This is an
 * app-local holder, not the core `backup/` JSON contract (that's WP-C2's `BackupV1` +
 * `BackupMapper`/`BackupCodec`, owned by a parallel worktree and not yet present here). WP-U5's
 * export/restore screens will map between this and that JSON contract.
 */
data class BackupData(
    val accounts: List<Account>,
    val categories: List<Category>,
    val transactions: List<Transaction>,
    val recurringRules: List<RecurringRule>,
    val scheduledItems: List<ScheduledItem>,
    val termPeriods: List<TermPeriod>,
    val predictions: List<Prediction>,
    val sinkingFunds: List<SinkingFund>,
    val scenarios: List<Scenario>,
    val presets: List<Preset>,
    val merchantRules: List<MerchantRule>,
    val reconciliations: List<Reconciliation>,
    val importBatches: List<ImportBatch>,
    val balanceSnapshots: List<BalanceSnapshot>,
    val categoryWeekSnapshots: List<CategoryWeekSnapshot>,
    val settings: BudgetSettings,
)
