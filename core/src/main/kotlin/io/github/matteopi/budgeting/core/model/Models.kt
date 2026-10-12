package io.github.matteopi.budgeting.core.model

import java.time.LocalDate

/**
 * Plain domain models shared by the forecast engine and the Android app.
 * The app's Room entities map 1:1 onto these. Ids are Room row ids (0 = not yet saved).
 * Only java.time and Kotlin stdlib APIs that exist on Android API 26 may be used in core.
 */

data class Account(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    /** >= 0. Arranged overdraft for current accounts; credit limit for credit cards. */
    val overdraftLimit: Money = Money.ZERO,
    /** Accounts in the "pool" count towards balance forecasts and safe-to-spend. */
    val includeInSafeToSpend: Boolean = true,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

data class Category(
    val id: Long = 0,
    val name: String,
    val flow: Flow,
    val forecastMode: ForecastMode,
    val colorArgb: Long? = null,
    val isSystem: Boolean = false,
    /** Stable key for system categories, e.g. [SystemKeys.UNTRACKED]. */
    val systemKey: String? = null,
    /** New recurring rules / scheduled items in this category default to "major income". */
    val isMajorIncomeDefault: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

object SystemKeys {
    const val UNTRACKED = "untracked"
}

data class Transaction(
    val id: Long = 0,
    val accountId: Long,
    /** Signed: negative = money left this account. */
    val amount: Money,
    val date: LocalDate,
    val createdAtEpochMillis: Long = 0,
    /** Null only for TRANSFER and OPENING_BALANCE. */
    val categoryId: Long?,
    /** Short free text, e.g. "Tesco" or "Meal deal". */
    val label: String = "",
    val note: String = "",
    val kind: TxKind = TxKind.NORMAL,
    /** Shared by both legs of a transfer. */
    val transferGroupId: String? = null,
    /** Set when this transaction was posted from a recurring rule (excluded from LEARNED stats). */
    val recurringRuleId: Long? = null,
    /** Set when posted from a scheduled item (excluded from LEARNED stats). */
    val scheduledItemId: Long? = null,
    val importBatchId: Long? = null,
    /** Dedupe key for imported statement rows. */
    val importKey: String? = null,
    /** Set on the per-category transactions a saved receipt was split into (§10.3). */
    val receiptId: Long? = null,
)

data class RecurringRule(
    val id: Long = 0,
    val label: String,
    val accountId: Long,
    val categoryId: Long?,
    /**
     * Signed amount (negative = outgoing). For transfers ([toAccountId] != null) this is the
     * positive amount moved from [accountId] to [toAccountId].
     */
    val amount: Money,
    val frequency: Frequency,
    /** Every [interval] units of [frequency]; >= 1. */
    val interval: Int = 1,
    val startDate: LocalDate,
    /** Inclusive; null = forever. */
    val endDate: LocalDate? = null,
    val postingMode: PostingMode,
    /** Occurrences on or before this date have been posted, confirmed or skipped. */
    val postedThrough: LocalDate? = null,
    val toAccountId: Long? = null,
    /** Marks a "money in" event that starts a new budget period (e.g. student loan). */
    val isMajorIncome: Boolean = false,
    val active: Boolean = true,
)

/** A one-off future amount on a specific date (e.g. a loan instalment, a deposit, a trip). */
data class ScheduledItem(
    val id: Long = 0,
    val label: String,
    val accountId: Long,
    val categoryId: Long?,
    /** Same sign/transfer convention as [RecurringRule.amount]. */
    val amount: Money,
    val date: LocalDate,
    val postingMode: PostingMode,
    val toAccountId: Long? = null,
    val isMajorIncome: Boolean = false,
    /** True once it became a real transaction (or was confirmed). */
    val posted: Boolean = false,
    /** True if the user said it will not happen. */
    val skipped: Boolean = false,
)

/** A term. Any date not inside a term is HOLIDAY. Dates inclusive. */
data class TermPeriod(
    val id: Long = 0,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/** The user's own estimate for a LEARNED category in one regime. */
data class Prediction(
    val id: Long = 0,
    val categoryId: Long,
    val regime: Regime,
    val mode: PredictionMode,
    /** Positive weekly magnitude (for PRIOR and REPLACE). Direction comes from the category's flow. */
    val weeklyAmount: Money = Money.ZERO,
    /** Used by ADJUST_PERCENT, e.g. -20 = 20% less than the automatic estimate. */
    val adjustPercent: Int = 0,
)

/** Money set aside virtually for a future cost (e.g. "Interrailing, £600 by 1 July"). */
data class SinkingFund(
    val id: Long = 0,
    val name: String,
    /** Positive. */
    val target: Money,
    val startDate: LocalDate,
    val targetDate: LocalDate,
    /** Category the eventual spending will use (optional). */
    val categoryId: Long? = null,
    val status: SinkingFundStatus = SinkingFundStatus.ACTIVE,
)

data class Scenario(
    val id: Long = 0,
    val name: String,
    val items: List<ScenarioItem> = emptyList(),
)

/** One hypothetical change in a what-if scenario. Fields used depend on [type]. */
data class ScenarioItem(
    val id: Long = 0,
    val scenarioId: Long = 0,
    val type: ScenarioItemType,
    val label: String = "",
    val accountId: Long? = null,
    val categoryId: Long? = null,
    /** ONE_OFF / RECURRING: signed amount. */
    val amount: Money = Money.ZERO,
    /** ONE_OFF: the date. RECURRING: start date. */
    val date: LocalDate? = null,
    val frequency: Frequency? = null,
    val interval: Int = 1,
    val endDate: LocalDate? = null,
    /** CATEGORY_ADJUST: e.g. -20 = spend 20% less in [categoryId]. */
    val percent: Int = 0,
    /** CATEGORY_ADJUST: null = both regimes. */
    val regime: Regime? = null,
)

/** One-tap quick-add button (e.g. "Meal deal £3.85"). */
data class Preset(
    val id: Long = 0,
    val label: String,
    /** Positive magnitude; direction from [flow]. */
    val amount: Money,
    val flow: Flow,
    val categoryId: Long,
    /** Null = use the default account. */
    val accountId: Long? = null,
    val showOnWidget: Boolean = true,
    val sortOrder: Int = 0,
)

/** Merchant memory for statement imports: normalised description key -> category. */
data class MerchantRule(
    val id: Long = 0,
    val merchantKey: String,
    val categoryId: Long,
    val label: String? = null,
)

data class Reconciliation(
    val id: Long = 0,
    val accountId: Long,
    val date: LocalDate,
    /** Balance the app had computed (raw, signed). */
    val expected: Money,
    /** Balance the user typed in from their bank app (raw, signed). */
    val actual: Money,
    /** The RECONCILIATION transaction created for the difference (null if difference was 0). */
    val transactionId: Long? = null,
    /** Day after the previous reconciliation of this account (or history start). Used to spread the gap. */
    val periodStart: LocalDate,
)

/** A stored prediction of the pool balance, for accuracy tracking. */
data class BalanceSnapshot(
    val id: Long = 0,
    val createdOn: LocalDate,
    val targetDate: LocalDate,
    /** 1 = tier 1 baseline, 3 = full model (tiers 2+3). */
    val tier: Int,
    val p10: Money?,
    val p50: Money,
    val p90: Money?,
)

/** A stored week-ahead prediction for one category, for accuracy tracking. */
data class CategoryWeekSnapshot(
    val id: Long = 0,
    val createdOn: LocalDate,
    /** Monday of the predicted week. */
    val weekStart: LocalDate,
    val categoryId: Long,
    val tier: Int,
    /** Signed (negative = spending). */
    val predicted: Money,
)

/** One statement import, so it can be listed and undone. */
data class ImportBatch(
    val id: Long = 0,
    val accountId: Long,
    val importedAtEpochMillis: Long,
    val fileName: String,
    val rowCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
)
