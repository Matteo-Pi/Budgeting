package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.CommittedFlow
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate

/** One row of the term-aware weekly Timeline (docs/PLAN.md §6.5 "Timeline"). */
data class TimelineWeek(
    val weekStart: LocalDate,
    val regime: Regime,
    /** The term's name when [regime] is TERM and a term covers most of the week; else null. */
    val termName: String?,
    /** Committed flows dated within this week, date-sorted. */
    val flows: List<CommittedFlow>,
    /** Sum of expected learned spending over the days of this week (signed; negative = spending). */
    val expectedLearned: Money,
    /** End-of-week pool balance, display terms. Null P10/P90 if Monte Carlo was skipped. */
    val endBalanceP10: Money?,
    val endBalanceP50: Money,
    val endBalanceP90: Money?,
    /** Money reserved in sinking funds at the end of this week. */
    val reserved: Money,
    /** True when this week's P10 is below the floor — highlighted in the UI. */
    val belowFloor: Boolean,
)

/**
 * Pure grouping of [ForecastResult.days] into Monday-start weeks, for the Timeline screen. No
 * Android/Compose imports, so it is unit-testable on the plain JVM.
 */
object TimelineMapping {
    fun buildWeeks(result: ForecastResult, terms: List<TermPeriod>, zeroPoint: ZeroPoint): List<TimelineWeek> {
        // days[0] is `today` itself (no flows yet); the timeline only covers today+1 onward.
        val forecastDays = result.days.drop(1)
        if (forecastDays.isEmpty()) return emptyList()

        val floorDisplay = Balances.toDisplay(result.floor, result.poolOverdraft, zeroPoint)
        val byWeek = forecastDays.groupBy { Regimes.weekStart(it.date) }

        return byWeek.entries.sortedBy { it.key }.map { (weekStart, weekDays) ->
            val lastDay = weekDays.maxBy { it.date }
            val weekEnd = weekStart.plusDays(6)
            val flows = result.committedFlows
                .filter { !it.date.isBefore(weekStart) && !it.date.isAfter(weekEnd) }
                .sortedBy { it.date }
            val expectedLearned = Money(weekDays.sumOf { it.expectedLearned.pence })
            val regime = Regimes.regimeOfWeek(weekStart, terms)
            val termName = if (regime == Regime.TERM) dominantTermName(weekStart, terms) else null

            val p10 = lastDay.p10?.let { Balances.toDisplay(it, result.poolOverdraft, zeroPoint) }
            val p50 = Balances.toDisplay(lastDay.p50 ?: lastDay.expectedBalance, result.poolOverdraft, zeroPoint)
            val p90 = lastDay.p90?.let { Balances.toDisplay(it, result.poolOverdraft, zeroPoint) }

            TimelineWeek(
                weekStart = weekStart,
                regime = regime,
                termName = termName,
                flows = flows,
                expectedLearned = expectedLearned,
                endBalanceP10 = p10,
                endBalanceP50 = p50,
                endBalanceP90 = p90,
                reserved = lastDay.reserved,
                belowFloor = p10 != null && p10 < floorDisplay,
            )
        }
    }

    /** The name of whichever [TermPeriod] covers the most days of the Mon-Sun week starting [weekStart]. */
    private fun dominantTermName(weekStart: LocalDate, terms: List<TermPeriod>): String? {
        val counts = (0..6)
            .mapNotNull { Regimes.termContaining(weekStart.plusDays(it.toLong()), terms) }
            .groupingBy { it.id }
            .eachCount()
        val topTermId = counts.entries.maxByOrNull { it.value }?.key ?: return null
        return terms.firstOrNull { it.id == topTermId }?.name
    }
}
