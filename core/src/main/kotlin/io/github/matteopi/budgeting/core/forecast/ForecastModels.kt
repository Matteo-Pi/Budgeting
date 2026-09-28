package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import java.time.LocalDate

/*
 * Contract between the forecast engine (core) and the app. See docs/PLAN.md §5.
 * Fields may be ADDED by the engine implementer; do not rename or remove them.
 *
 * All balances are RAW (the bank's view, signed). Converting to the user's chosen
 * zero point is a display concern (see Balances.toDisplay).
 */

data class ForecastInput(
    val today: LocalDate,
    val accounts: List<Account>,
    val categories: List<Category>,
    /** All transactions (the engine filters by kind, pool, history window). */
    val transactions: List<Transaction>,
    val recurringRules: List<RecurringRule>,
    val scheduledItems: List<ScheduledItem>,
    val termPeriods: List<TermPeriod>,
    val predictions: List<Prediction>,
    val sinkingFunds: List<SinkingFund>,
    val reconciliations: List<Reconciliation>,
    val settings: BudgetSettings,
    /** Optional what-if overlay. The base data is never modified. */
    val scenario: Scenario? = null,
    /** False = skip tier 3 (fast path for the widget). */
    val runMonteCarlo: Boolean = true,
    /** Seed for tier 3. Default: derived from [today] so results are stable within a day. */
    val seed: Long = today.toEpochDay(),
)

data class ForecastResult(
    val today: LocalDate,
    /** Last day shown in the forecast (today + forecastHorizonDays). */
    val forecastEnd: LocalDate,
    /** Current summed balance of pool accounts (raw). */
    val poolBalanceNow: Money,
    /** Sum of overdraft limits of pool accounts (positive). */
    val poolOverdraft: Money,
    /** Raw-terms floor: 0 (ACCOUNT_BALANCE) or -poolOverdraft (OVERDRAFT_LIMIT). */
    val floor: Money,
    val days: List<DayForecast>,
    val categoryEstimates: List<CategoryEstimate>,
    /** (category, regime) pairs where data is too thin and the user hasn't given a prediction. */
    val needsPrediction: List<PredictionRequest>,
    val safeToSpend: SafeToSpend,
    /** Null when Monte Carlo was skipped. */
    val risk: RiskSummary?,
    val budgetPeriods: List<BudgetPeriod>,
    val fundReservations: List<FundReservation>,
    /** Every known future flow in the horizon (recurring, scheduled, funds, scenario), date-sorted. */
    val committedFlows: List<CommittedFlow>,
    /** Tier 1 weekly estimate per LEARNED category id (signed), for accuracy comparisons. */
    val tier1WeeklyByCategory: Map<Long, Money>,
    /** First day of the history window used for learning (null = no history yet). */
    val historyStart: LocalDate?,
    /** Number of complete Mon–Sun weeks inside the history window. */
    val completeWeeksOfHistory: Int,
    /**
     * The same forecast split per account, for every non-archived account (pool and non-pool),
     * ordered by account id. For pool accounts the expected balances sum exactly to
     * [DayForecast.expectedBalance]; percentiles are per account and are not additive.
     */
    val accountForecasts: List<AccountForecast> = emptyList(),
)

data class AccountForecast(
    val accountId: Long,
    /** True if the account counts towards the pool (safe-to-spend and the total line). */
    val inPool: Boolean,
    /** Raw balance today. */
    val balanceNow: Money,
    /** The account's own overdraft limit (positive), for display conversion. */
    val overdraft: Money,
    /** One entry per day, aligned with [ForecastResult.days] (starts today). */
    val days: List<AccountDayForecast>,
)

data class AccountDayForecast(
    val date: LocalDate,
    /** End-of-day raw balance using tier 2 point estimates. */
    val expectedBalance: Money,
    /** Tier 3 percentiles for this account (null if Monte Carlo skipped). */
    val p10: Money?,
    val p50: Money?,
    val p90: Money?,
)

data class DayForecast(
    val date: LocalDate,
    val regime: Regime,
    /** Net committed flows on this day (pool terms). */
    val committedNet: Money,
    /** Expected learned flows on this day (tier 2; negative = net spending). */
    val expectedLearned: Money,
    /** End-of-day pool balance using tier 1 only (committed + flat 8-week medians). */
    val tier1Balance: Money,
    /** End-of-day pool balance using tier 2 point estimates. */
    val expectedBalance: Money,
    /** Tier 3 percentiles of end-of-day pool balance (null if Monte Carlo skipped). */
    val p10: Money?,
    val p50: Money?,
    val p90: Money?,
    /** Total sinking-fund money earmarked at the end of this day. */
    val reserved: Money,
)

enum class EstimateSource {
    /** Enough data in this regime; data dominates. */
    DATA,

    /** Some data, shrunk toward the fallback / prior. */
    BLENDED,

    /** No usable data in this regime: using the category's all-regime estimate. */
    FALLBACK_OVERALL,
    PREDICTION_PRIOR,
    PREDICTION_REPLACE,
    PREDICTION_ADJUSTED,

    /** Nothing at all (treated as 0). */
    NONE,
}

enum class DataSufficiency { TRUSTED, THIN, TOO_THIN }

data class CategoryEstimate(
    val categoryId: Long,
    val regime: Regime,
    /** Signed weekly amount (negative = spending). */
    val weeklyAmount: Money,
    /** The automatic estimate before any user prediction was applied (signed). */
    val automaticWeeklyAmount: Money,
    val source: EstimateSource,
    /** Complete weeks of data in this regime inside the history window. */
    val weeksOfData: Int,
    val sufficiency: DataSufficiency,
    /** Share of the weekly amount falling on Mon..Sun; 7 values summing to 1. */
    val dayOfWeekShares: List<Double>,
)

data class PredictionRequest(
    val categoryId: Long,
    val regime: Regime,
    /** Best automatic guess to pre-fill (positive magnitude), if any. */
    val suggestedWeekly: Money?,
)

data class SafeToSpend(
    /** How much can go on LEARNED spending per week and still stay above floor + buffer. >= 0. */
    val weekly: Money,
    val perDay: Money,
    /** Last day covered: the day before the next major income, capped at the forecast end. */
    val horizonEnd: LocalDate,
    val nextMajorIncome: CommittedFlow?,
    val daysUntilNextMajorIncome: Int?,
    /** The date that limits the answer (tightest point), if any. */
    val bindingDate: LocalDate?,
    /** Human-readable reason, e.g. "Rent & bills on 1 Oct". */
    val bindingReason: String?,
    /** > 0 when even zero spending breaches floor + buffer by this much. */
    val shortfall: Money,
    /** The model's expected weekly LEARNED spending for comparison (positive magnitude). */
    val predictedWeeklySpending: Money,
)

data class RiskSummary(
    /** Probability the pool dips below floor + buffer before the safe-to-spend horizon ends. */
    val probBelowFloor: Double,
    /** Probability the pool goes past the arranged overdraft limit (the hard limit). */
    val probBeyondOverdraft: Double,
    /** First date where P10 is below floor + buffer, if any. */
    val firstLikelyBreach: LocalDate?,
    val endBalanceP10: Money,
    val endBalanceP50: Money,
    val endBalanceP90: Money,
)

/** The stretch between two major incomes (how long each lump has to last). */
data class BudgetPeriod(
    val start: LocalDate,
    /** Inclusive. */
    val end: LocalDate,
    /** Major income that starts the period (zero for the current partial period). */
    val startingIncome: Money,
    val committedOut: Money,
    val committedIn: Money,
    /** Money for LEARNED spending over the period after committed flows, floor and buffer. */
    val availableForSpending: Money,
    val weeks: Double,
    val perWeek: Money,
)

data class FundReservation(
    val fundId: Long,
    val name: String,
    val target: Money,
    val targetDate: LocalDate,
    val reservedToday: Money,
    val weeklyContribution: Money,
)

enum class FlowSource {
    RECURRING, SCHEDULED, SINKING_FUND, SCENARIO, OVERDUE_CONFIRM,

    /**
     * Added by the engine: a real ledger [io.github.matteopi.budgeting.core.model.Transaction]
     * dated after today. The UI shouldn't allow this, but the engine still reports it as a
     * committed flow defensively (see `docs/PLAN.md` §5.5 point 3).
     */
    TRANSACTION,
}

data class CommittedFlow(
    val date: LocalDate,
    /** Signed effect on the pool (transfers inside the pool are omitted). */
    val amount: Money,
    val label: String,
    val source: FlowSource,
    val sourceId: Long?,
    val categoryId: Long?,
    val accountId: Long?,
    val isMajorIncome: Boolean,
)
