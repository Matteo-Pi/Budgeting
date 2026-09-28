package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode

/**
 * Tier 1: a flat, regime-blind baseline (an 8-week median spread evenly over the week), used
 * for [DayForecast.tier1Balance] and as the simple point of comparison for accuracy tracking.
 * See `docs/PLAN.md` §5.7.
 */
object Tier1 {
    /** Median weekly signed amount per learned category. */
    fun weeklyEstimates(history: History, input: ForecastInput): Map<Long, Money> {
        val learned = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }
        return learned.associate { it.id to weeklyEstimate(it, history, input.predictions) }
    }

    /** The same total, spread evenly over 7 days: `Σ_c μ1[c] / 7`, added identically every day. */
    fun dailyTotal(weeklyEstimates: Map<Long, Money>): Money =
        Money(weeklyEstimates.values.sumOf { it.pence / 7 })

    private fun weeklyEstimate(category: Category, history: History, predictions: List<Prediction>): Money {
        val weeks = history.weeklyByCategory[category.id].orEmpty()
        val recent = weeks.takeLast(ForecastConstants.TIER1_WEEKS)
        if (recent.isNotEmpty()) return median(recent)

        // No history at all: fall back to the average of the user's own PRIOR/REPLACE
        // predictions for this category, across regimes.
        val ownPredictions = predictions.filter {
            it.categoryId == category.id && (it.mode == PredictionMode.PRIOR || it.mode == PredictionMode.REPLACE)
        }
        if (ownPredictions.isEmpty()) return Money.ZERO
        val total = ownPredictions.sumOf { signedWeekly(it, category).pence }
        return Money(total / ownPredictions.size)
    }
}

/**
 * Median of money amounts by pence value. An even count averages the two middle values
 * (rounding toward zero, matching integer division).
 */
internal fun median(values: List<Money>): Money {
    if (values.isEmpty()) return Money.ZERO
    val sorted = values.map { it.pence }.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        Money(sorted[mid])
    } else {
        Money((sorted[mid - 1] + sorted[mid]) / 2)
    }
}

/**
 * [Prediction.weeklyAmount] is a positive magnitude for OUT/IN categories; the sign comes from
 * the category's flow (OUT negative, IN positive). The system Untracked category has flow BOTH
 * (no implied direction): its predictions are interpreted as an already-signed net weekly
 * amount rather than a magnitude, since "the user's estimate of untracked leakage" can
 * legitimately be a net inflow or outflow.
 */
internal fun signedWeekly(prediction: Prediction, category: Category): Money = when (category.flow) {
    Flow.OUT -> -prediction.weeklyAmount
    Flow.IN -> prediction.weeklyAmount
    Flow.BOTH -> prediction.weeklyAmount
}
