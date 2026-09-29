package io.github.matteopi.budgeting.core.analysis

import io.github.matteopi.budgeting.core.forecast.CategoryEstimate
import io.github.matteopi.budgeting.core.forecast.EstimateSource
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.DayOfWeek
import java.time.LocalDate

/** One unusual thing noticed in recent spending. See docs/PLAN.md §5.14. */
data class Anomaly(
    val kind: AnomalyKind,
    val categoryId: Long?,
    val weekStart: LocalDate,
    /** Magnitude actually logged. */
    val actual: Money,
    /** Magnitude that was expected. */
    val expected: Money,
    val message: String,
)

enum class AnomalyKind { HIGH_CATEGORY, HIGH_TOTAL, LOW_LOGGING }

/**
 * Looks for spending that stands out this week or last week: a single category running hot,
 * total discretionary spending running hot, or a suspiciously quiet week that might mean the
 * user forgot to log something.
 *
 * Written against the public [ForecastInput]/[ForecastResult] contract only; it does not use
 * any other file in `forecast/`, and re-derives the small pieces of calendar/aggregation logic
 * it needs (a little duplication is fine, see docs/PLAN.md §5.14).
 */
object Anomalies {

    private val HIGH_CATEGORY_THRESHOLD = Money.pounds(15)
    private val HIGH_TOTAL_THRESHOLD = Money.pounds(25)
    private val LOW_LOGGING_MIN_EXPECTED = Money.pounds(30)

    fun detect(input: ForecastInput, result: ForecastResult): List<Anomaly> {
        val poolAccountIds = input.accounts
            .filter { it.includeInSafeToSpend && !it.archived }
            .map { it.id }
            .toSet()
        if (poolAccountIds.isEmpty()) return emptyList()

        val discretionary = input.categories.filter {
            it.forecastMode == ForecastMode.LEARNED && it.flow == Flow.OUT && !it.isSystem && !it.archived
        }
        if (discretionary.isEmpty()) return emptyList()

        val estimateByKey = result.categoryEstimates.associateBy { it.categoryId to it.regime }

        val today = input.today
        val thisWeekStart = mondayOnOrBefore(today)
        val lastWeekStart = thisWeekStart.minusDays(7)
        val lastWeekEnd = thisWeekStart.minusDays(1)

        val anomalies = mutableListOf<Anomaly>()

        anomalies += weekAnomalies(
            weekStart = thisWeekStart,
            weekEnd = today,
            daysCovered = daysOfWeekBetween(thisWeekStart, today),
            regime = regimeOfWeek(thisWeekStart, input.termPeriods),
            isCurrentWeek = true,
            discretionary = discretionary,
            estimateByKey = estimateByKey,
            poolAccountIds = poolAccountIds,
            transactions = input.transactions,
        )

        val lastWeekRegime = regimeOfWeek(lastWeekStart, input.termPeriods)
        anomalies += weekAnomalies(
            weekStart = lastWeekStart,
            weekEnd = lastWeekEnd,
            daysCovered = daysOfWeekBetween(lastWeekStart, lastWeekEnd),
            regime = lastWeekRegime,
            isCurrentWeek = false,
            discretionary = discretionary,
            estimateByKey = estimateByKey,
            poolAccountIds = poolAccountIds,
            transactions = input.transactions,
        )

        // LOW_LOGGING: last complete week only.
        val lastWeekActual = totalDiscretionary(
            discretionary, poolAccountIds, input.transactions, lastWeekStart, lastWeekEnd,
        )
        val lastWeekExpected = totalExpected(
            discretionary, estimateByKey, lastWeekRegime, daysOfWeekBetween(lastWeekStart, lastWeekEnd),
        )
        val recentReconciliation = input.reconciliations.any {
            !it.date.isBefore(today.minusDays(6)) && !it.date.isAfter(today)
        }
        // Only for a week the user was tracking from start to finish: straight after setting the app
        // up, last week has nothing logged simply because they weren't using it yet.
        val trackingStart = input.transactions.filter { it.accountId in poolAccountIds }.minOfOrNull { it.date }
        val trackedAllLastWeek = trackingStart != null && !trackingStart.isAfter(lastWeekStart)
        if (trackedAllLastWeek &&
            lastWeekExpected >= LOW_LOGGING_MIN_EXPECTED &&
            lastWeekActual < lastWeekExpected.scale(0.4) &&
            !recentReconciliation
        ) {
            anomalies += Anomaly(
                kind = AnomalyKind.LOW_LOGGING,
                categoryId = null,
                weekStart = lastWeekStart,
                actual = lastWeekActual,
                expected = lastWeekExpected,
                message = "Quiet week — did you forget to log something? Update your balances to check.",
            )
        }

        return anomalies
    }

    private fun weekAnomalies(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        daysCovered: List<DayOfWeek>,
        regime: Regime,
        isCurrentWeek: Boolean,
        discretionary: List<Category>,
        estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
        poolAccountIds: Set<Long>,
        transactions: List<Transaction>,
    ): List<Anomaly> {
        val label = if (isCurrentWeek) "this week" else "last week"
        val result = mutableListOf<Anomaly>()
        var totalActual = Money.ZERO
        var totalExpected = Money.ZERO

        for (category in discretionary) {
            val estimate = estimateByKey[category.id to regime]
            val actual = categoryActual(category.id, poolAccountIds, transactions, weekStart, weekEnd)
            val expected = expectedForDays(estimate, daysCovered)
            totalActual += actual
            if (estimate != null && estimate.source != EstimateSource.NONE) {
                totalExpected += expected
            }

            if (estimate != null && estimate.source != EstimateSource.NONE &&
                actual > expected.scale(1.5) &&
                (actual - expected) >= HIGH_CATEGORY_THRESHOLD
            ) {
                result += Anomaly(
                    kind = AnomalyKind.HIGH_CATEGORY,
                    categoryId = category.id,
                    weekStart = weekStart,
                    actual = actual,
                    expected = expected,
                    message = "${category.name} $label: ${actual.format(showPence = false)} " +
                        "vs usual ${expected.format(showPence = false)}",
                )
            }
        }

        if (totalActual > totalExpected.scale(1.5) &&
            (totalActual - totalExpected) >= HIGH_TOTAL_THRESHOLD
        ) {
            result += Anomaly(
                kind = AnomalyKind.HIGH_TOTAL,
                categoryId = null,
                weekStart = weekStart,
                actual = totalActual,
                expected = totalExpected,
                message = "Spending $label: ${totalActual.format(showPence = false)} " +
                    "vs usual ${totalExpected.format(showPence = false)}",
            )
        }

        return result
    }

    private fun totalDiscretionary(
        discretionary: List<Category>,
        poolAccountIds: Set<Long>,
        transactions: List<Transaction>,
        weekStart: LocalDate,
        weekEnd: LocalDate,
    ): Money {
        var total = Money.ZERO
        for (category in discretionary) {
            total += categoryActual(category.id, poolAccountIds, transactions, weekStart, weekEnd)
        }
        return total
    }

    private fun totalExpected(
        discretionary: List<Category>,
        estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
        regime: Regime,
        daysCovered: List<DayOfWeek>,
    ): Money {
        var total = Money.ZERO
        for (category in discretionary) {
            val estimate = estimateByKey[category.id to regime] ?: continue
            if (estimate.source == EstimateSource.NONE) continue
            total += expectedForDays(estimate, daysCovered)
        }
        return total
    }

    /** Sum of magnitudes of eligible discretionary transactions in [weekStart, weekEnd]. */
    private fun categoryActual(
        categoryId: Long,
        poolAccountIds: Set<Long>,
        transactions: List<Transaction>,
        weekStart: LocalDate,
        weekEnd: LocalDate,
    ): Money {
        var total = Money.ZERO
        for (tx in transactions) {
            if (tx.kind != TxKind.NORMAL) continue
            if (tx.categoryId != categoryId) continue
            if (tx.accountId !in poolAccountIds) continue
            if (tx.recurringRuleId != null || tx.scheduledItemId != null) continue
            if (tx.date.isBefore(weekStart) || tx.date.isAfter(weekEnd)) continue
            total += tx.amount.abs()
        }
        return total
    }

    private fun expectedForDays(estimate: CategoryEstimate?, daysCovered: List<DayOfWeek>): Money {
        if (estimate == null || daysCovered.isEmpty()) return Money.ZERO
        val shareSum = daysCovered.sumOf { estimate.dayOfWeekShares.getOrElse(it.value - 1) { 0.0 } }
        return estimate.weeklyAmount.abs().scale(shareSum)
    }

    private fun daysOfWeekBetween(from: LocalDate, to: LocalDate): List<DayOfWeek> {
        if (to.isBefore(from)) return emptyList()
        val days = mutableListOf<DayOfWeek>()
        var d = from
        while (!d.isAfter(to)) {
            days += d.dayOfWeek
            d = d.plusDays(1)
        }
        return days
    }

    private fun mondayOnOrBefore(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    private fun regimeOf(date: LocalDate, terms: List<TermPeriod>): Regime =
        if (terms.any { !date.isBefore(it.startDate) && !date.isAfter(it.endDate) }) Regime.TERM else Regime.HOLIDAY

    private fun regimeOfWeek(weekStart: LocalDate, terms: List<TermPeriod>): Regime {
        val termDays = (0..6).count { regimeOf(weekStart.plusDays(it.toLong()), terms) == Regime.TERM }
        return if (termDays >= 4) Regime.TERM else Regime.HOLIDAY
    }
}
