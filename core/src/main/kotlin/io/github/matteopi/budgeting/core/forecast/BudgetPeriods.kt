package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Splits the horizon into lumpy-income budget periods. See `docs/PLAN.md` §5.12. */
object BudgetPeriods {
    fun compute(
        input: ForecastInput,
        today: LocalDate,
        forecastEnd: LocalDate,
        committedFlows: List<CommittedFlow>,
        estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
        dayOfWeekShares: Map<Long, List<Double>>,
        poolBalanceNow: Money,
        floor: Money,
    ): List<BudgetPeriod> {
        val terms = input.termPeriods
        val buffer = input.settings.buffer
        val learned = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }
        val activeFunds = input.sinkingFunds.filter { it.status == SinkingFundStatus.ACTIVE }

        val majorIncomeDates = committedFlows.asSequence()
            .filter { it.isMajorIncome && it.amount.isPositive }
            .map { it.date }
            .distinct()
            .sorted()
            .toList()

        data class Bound(val start: LocalDate, val end: LocalDate, val startingIncome: Money)

        val bounds = mutableListOf<Bound>()
        val firstBoundary = majorIncomeDates.firstOrNull()
        val currentEnd = (firstBoundary?.minusDays(1)) ?: forecastEnd
        if (!currentEnd.isBefore(today.plusDays(1))) {
            bounds += Bound(today.plusDays(1), currentEnd, Money.ZERO)
        }
        for (i in majorIncomeDates.indices) {
            val start = majorIncomeDates[i]
            if (start.isAfter(forecastEnd)) break
            val end = if (i + 1 < majorIncomeDates.size) majorIncomeDates[i + 1].minusDays(1) else forecastEnd
            val cappedEnd = if (end.isAfter(forecastEnd)) forecastEnd else end
            if (cappedEnd.isBefore(start)) continue
            val startingIncome = Money(
                committedFlows.filter { it.isMajorIncome && it.amount.isPositive && it.date == start }
                    .sumOf { it.amount.pence },
            )
            bounds += Bound(start, cappedEnd, startingIncome)
        }
        if (bounds.isEmpty()) {
            // No major income anywhere in the horizon: the whole horizon is one "current" period.
            bounds += Bound(today.plusDays(1), forecastEnd, Money.ZERO)
        }

        val reservedToday = SinkingFunds.totalReserved(input.sinkingFunds, today)

        return bounds.mapIndexed { index, bound ->
            val periodFlows = committedFlows.filter { !it.date.isBefore(bound.start) && !it.date.isAfter(bound.end) }
            val nonFundNonMajor = periodFlows.filter {
                it.source != FlowSource.SINKING_FUND && !(it.isMajorIncome && it.amount.isPositive)
            }
            val committedOut = Money(nonFundNonMajor.filter { it.amount.isNegative }.sumOf { it.amount.pence })
            val committedIn = Money(nonFundNonMajor.filter { it.amount.isPositive }.sumOf { it.amount.pence })

            var learnedTotal = 0L
            var d = bound.start
            while (!d.isAfter(bound.end)) {
                learnedTotal += restrictedLearnedDay(d, terms, learned, estimateByKey, dayOfWeekShares).pence
                d = d.plusDays(1)
            }

            var fundAllocation = 0L
            for (fund in activeFunds) {
                val before = SinkingFunds.reserved(fund, bound.start.minusDays(1)).pence
                val after = SinkingFunds.reserved(fund, bound.end).pence
                fundAllocation += after - before
                if (!fund.targetDate.isBefore(bound.start) && !fund.targetDate.isAfter(bound.end)) {
                    fundAllocation += fund.target.pence
                }
            }

            // A boundary period's startingIncome is always positive (it's the sum of major
            // income amounts that land on it); only the pre-first-major "current" period (or
            // the sole period when there's no major income at all) has a zero one.
            val isCurrent = index == 0 && bound.startingIncome.isZero
            val available = if (isCurrent) {
                poolBalanceNow.pence - reservedToday.pence - floor.pence - buffer.pence +
                    committedIn.pence + committedOut.pence + learnedTotal - fundAllocation
            } else {
                bound.startingIncome.pence + committedIn.pence + committedOut.pence + learnedTotal - fundAllocation
            }

            val days = ChronoUnit.DAYS.between(bound.start, bound.end) + 1
            val weeks = days / 7.0
            val perWeek = Money(Math.round(available / weeks))

            BudgetPeriod(
                start = bound.start,
                end = bound.end,
                startingIncome = bound.startingIncome,
                committedOut = committedOut,
                committedIn = committedIn,
                availableForSpending = Money(available),
                weeks = weeks,
                perWeek = perWeek,
            )
        }
    }
}
