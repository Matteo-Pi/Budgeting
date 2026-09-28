package io.github.matteopi.budgeting.core.analysis

import io.github.matteopi.budgeting.core.forecast.CategoryEstimate
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate

/** Builds a [ForecastResult] with sensible empty defaults, for tests that only care about a few fields. */
fun testForecastResult(
    today: LocalDate,
    categoryEstimates: List<CategoryEstimate> = emptyList(),
    days: List<io.github.matteopi.budgeting.core.forecast.DayForecast> = emptyList(),
    tier1WeeklyByCategory: Map<Long, Money> = emptyMap(),
): ForecastResult = ForecastResult(
    today = today,
    forecastEnd = today.plusDays(182),
    poolBalanceNow = Money.ZERO,
    poolOverdraft = Money.ZERO,
    floor = Money.ZERO,
    days = days,
    categoryEstimates = categoryEstimates,
    needsPrediction = emptyList(),
    safeToSpend = SafeToSpend(
        weekly = Money.ZERO,
        perDay = Money.ZERO,
        horizonEnd = today,
        nextMajorIncome = null,
        daysUntilNextMajorIncome = null,
        bindingDate = null,
        bindingReason = null,
        shortfall = Money.ZERO,
        predictedWeeklySpending = Money.ZERO,
    ),
    risk = null,
    budgetPeriods = emptyList(),
    fundReservations = emptyList(),
    committedFlows = emptyList(),
    tier1WeeklyByCategory = tier1WeeklyByCategory,
    historyStart = null,
    completeWeeksOfHistory = 0,
)

val uniformDayShares: List<Double> = List(7) { 1.0 / 7 }
