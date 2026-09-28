package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

private val BINDING_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

/** The headroom-based "safe to spend per week" calculation. See `docs/PLAN.md` §5.11. */
object SafeToSpendCalculator {
    fun compute(
        input: ForecastInput,
        today: LocalDate,
        forecastEnd: LocalDate,
        committedFlows: List<CommittedFlow>,
        estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
        dayOfWeekShares: Map<Long, List<Double>>,
        poolBalanceNow: Money,
        floor: Money,
    ): SafeToSpend {
        val terms = input.termPeriods
        val buffer = input.settings.buffer
        val learned = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }

        // committedFlows is already date-sorted, so the first match is chronologically first.
        val majorIncomes = committedFlows.filter { it.isMajorIncome && it.amount.isPositive && it.date.isAfter(today) }
        val nextMajorIncome = majorIncomes.firstOrNull()
        val daysUntilNextMajorIncome = nextMajorIncome?.let { ChronoUnit.DAYS.between(today, it.date).toInt() }

        val horizonEnd: LocalDate = run {
            if (nextMajorIncome == null) return@run forecastEnd
            val daysAway = ChronoUnit.DAYS.between(today, nextMajorIncome.date)
            val target = if (daysAway < 7) majorIncomes.getOrNull(1) else nextMajorIncome
            val h = target?.date?.minusDays(1) ?: forecastEnd
            if (h.isAfter(forecastEnd)) forecastEnd else h
        }

        val committedByDate = committedFlows.groupBy { it.date }

        var cumCommitted = 0L
        var cumRestrictedLearned = 0L
        var minW = Double.POSITIVE_INFINITY
        var minWDate: LocalDate? = null
        var minH = Double.POSITIVE_INFINITY

        var d = today.plusDays(1)
        var k = 1L
        while (!d.isAfter(horizonEnd)) {
            cumCommitted += committedByDate[d].orEmpty().sumOf { it.amount.pence }
            cumRestrictedLearned += restrictedLearnedDay(d, terms, learned, estimateByKey, dayOfWeekShares).pence
            val reserved = SinkingFunds.totalReserved(input.sinkingFunds, d).pence
            val h = (poolBalanceNow.pence + cumCommitted + cumRestrictedLearned - reserved - floor.pence - buffer.pence).toDouble()
            val w = 7.0 * h / k
            if (w < minW) {
                minW = w
                minWDate = d
            }
            if (h < minH) minH = h
            d = d.plusDays(1)
            k++
        }

        val weekly = if (minWDate != null) Money(maxOf(0L, floor(minW).toLong())) else Money.ZERO
        val perDay = Money(weekly.pence / 7)
        val shortfall = if (minWDate != null) Money(maxOf(0L, Math.round(-minH))) else Money.ZERO

        val bindingReason = minWDate?.let { bindingDate ->
            val windowStart = bindingDate.minusDays(2)
            val largestOutflow = committedFlows
                .filter { !it.date.isBefore(windowStart) && !it.date.isAfter(bindingDate) && it.amount.isNegative }
                .minByOrNull { it.amount.pence }
            when {
                largestOutflow != null -> "${largestOutflow.label} on ${BINDING_DATE_FORMAT.format(largestOutflow.date)}"
                bindingDate == horizonEnd && nextMajorIncome != null ->
                    "Until ${nextMajorIncome.label} on ${BINDING_DATE_FORMAT.format(nextMajorIncome.date)}"
                else -> null
            }
        }

        return SafeToSpend(
            weekly = weekly,
            perDay = perDay,
            horizonEnd = horizonEnd,
            nextMajorIncome = nextMajorIncome,
            daysUntilNextMajorIncome = daysUntilNextMajorIncome,
            bindingDate = minWDate,
            bindingReason = bindingReason,
            shortfall = shortfall,
            predictedWeeklySpending = predictedWeeklySpending(today, terms, learned, estimateByKey),
        )
    }

    private fun predictedWeeklySpending(
        today: LocalDate,
        terms: List<TermPeriod>,
        learned: List<Category>,
        estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
    ): Money {
        val regime = Regimes.regimeOf(today, terms)
        val total = learned.filter { it.flow == Flow.OUT }
            .sumOf { abs((estimateByKey[it.id to regime]?.weeklyAmount ?: Money.ZERO).pence) }
        return Money(total)
    }
}

/**
 * Cumulative-to-date expected learned flow, restricted to non-discretionary categories: learned
 * OUT categories (discretionary spending -- what safe-to-spend is a budget for) are excluded
 * entirely; learned IN categories count at 75% (conservative, in case they don't arrive); the
 * Untracked category (flow BOTH) counts at 100%.
 */
internal fun restrictedLearnedDay(
    date: LocalDate,
    terms: List<TermPeriod>,
    learned: List<Category>,
    estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
    dayOfWeekShares: Map<Long, List<Double>>,
): Money {
    var total = 0L
    for (category in learned) {
        val weight = when (category.flow) {
            Flow.OUT -> continue
            Flow.IN -> 0.75
            Flow.BOTH -> 1.0
        }
        val amount = categoryDayAmount(category, date, terms, estimateByKey, dayOfWeekShares)
        total += Math.round(amount.pence * weight)
    }
    return Money(total)
}
