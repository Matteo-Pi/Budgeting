package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate
import kotlin.math.abs

/**
 * Tier 2: shrinkage estimates per (category, regime) plus a learned day-of-week spending
 * shape. See `docs/PLAN.md` §5.8.
 */
object Tier2 {
    data class Result(
        /** One entry per (learned category, regime): two rows per category. */
        val estimates: List<CategoryEstimate>,
        val estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
        val needsPrediction: List<PredictionRequest>,
        /** categoryId -> 7 day-of-week shares (Mon..Sun), summing to 1. */
        val dayOfWeekShares: Map<Long, List<Double>>,
    )

    fun compute(history: History, input: ForecastInput, today: LocalDate, forecastEnd: LocalDate): Result {
        val learned = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }
        val shares = dayOfWeekShares(history, learned)
        val regimesInHorizon = regimesInRange(today, forecastEnd, input.termPeriods)

        val estimates = mutableListOf<CategoryEstimate>()
        val needsPrediction = mutableListOf<PredictionRequest>()

        for (category in learned) {
            val weeks = history.weeklyByCategory[category.id].orEmpty()
            val categoryShares = shares[category.id] ?: UNIFORM_SHARES
            for (regime in Regime.entries) {
                val base = estimateFor(category, regime, history, weeks, input.predictions)
                val adjusted = applyScenarioAdjust(base, category, regime, input)
                val withShares = adjusted.copy(dayOfWeekShares = categoryShares)
                estimates += withShares

                if (needsPredictionFor(withShares, category, regime, input.predictions, regimesInHorizon)) {
                    val suggested = if (withShares.source == EstimateSource.NONE) null else withShares.automaticWeeklyAmount.abs()
                    needsPrediction += PredictionRequest(category.id, regime, suggested)
                }
            }
        }

        val byKey = estimates.associateBy { it.categoryId to it.regime }
        return Result(estimates, byKey, needsPrediction, shares)
    }

    private fun estimateFor(
        category: Category,
        regime: Regime,
        history: History,
        weeks: List<Money>,
        predictions: List<Prediction>,
    ): CategoryEstimate {
        val regimeIndices = history.weeks.indices.filter { history.weeks[it].regime == regime }
        val nR = regimeIndices.size
        val mR = median(regimeIndices.takeLast(ForecastConstants.RECENT_WEEKS).map { weeks[it] })

        val recentAllIndices = history.weeks.indices.toList().takeLast(ForecastConstants.RECENT_WEEKS)
        val nAll = recentAllIndices.size
        val mAll = median(recentAllIndices.map { weeks[it] })
        val fallback = if (nAll >= ForecastConstants.FALLBACK_MIN_WEEKS) mAll else null

        val w = nR / (nR + ForecastConstants.SHRINK_K)
        val automatic = if (nR >= 1) weightedSum(w, mR, fallback ?: mR) else (fallback ?: Money.ZERO)

        val sufficiency = when {
            nR >= ForecastConstants.TRUSTED_WEEKS -> DataSufficiency.TRUSTED
            nR >= ForecastConstants.THIN_WEEKS -> DataSufficiency.THIN
            else -> DataSufficiency.TOO_THIN
        }

        val prediction = predictions.firstOrNull { it.categoryId == category.id && it.regime == regime }
        val (finalAmount, source) = if (prediction == null) {
            automatic to when {
                nR >= ForecastConstants.TRUSTED_WEEKS -> EstimateSource.DATA
                nR >= 1 -> EstimateSource.BLENDED
                fallback != null -> EstimateSource.FALLBACK_OVERALL
                else -> EstimateSource.NONE
            }
        } else {
            val signed = signedWeekly(prediction, category)
            when (prediction.mode) {
                PredictionMode.REPLACE -> signed to EstimateSource.PREDICTION_REPLACE
                PredictionMode.ADJUST_PERCENT ->
                    automatic.scale(1 + prediction.adjustPercent / 100.0) to EstimateSource.PREDICTION_ADJUSTED
                PredictionMode.PRIOR -> if (nR >= 1) {
                    weightedSum(w, mR, signed) to
                        if (nR >= ForecastConstants.TRUSTED_WEEKS) EstimateSource.DATA else EstimateSource.BLENDED
                } else {
                    signed to EstimateSource.PREDICTION_PRIOR
                }
            }
        }

        return CategoryEstimate(
            categoryId = category.id,
            regime = regime,
            weeklyAmount = finalAmount,
            automaticWeeklyAmount = automatic,
            source = source,
            weeksOfData = nR,
            sufficiency = sufficiency,
            dayOfWeekShares = UNIFORM_SHARES, // replaced by the caller once computed
        )
    }

    private fun applyScenarioAdjust(
        estimate: CategoryEstimate,
        category: Category,
        regime: Regime,
        input: ForecastInput,
    ): CategoryEstimate {
        val scenario = input.scenario ?: return estimate
        var amount = estimate.weeklyAmount
        // Multiple matching CATEGORY_ADJUST items (unusual, but not forbidden by the model)
        // compound in list order.
        for (item in scenario.items) {
            if (item.type != ScenarioItemType.CATEGORY_ADJUST) continue
            if (item.categoryId != category.id) continue
            if (item.regime != null && item.regime != regime) continue
            amount = amount.scale(1 + item.percent / 100.0)
        }
        return if (amount == estimate.weeklyAmount) estimate else estimate.copy(weeklyAmount = amount)
    }

    private fun needsPredictionFor(
        estimate: CategoryEstimate,
        category: Category,
        regime: Regime,
        predictions: List<Prediction>,
        regimesInHorizon: Set<Regime>,
    ): Boolean {
        if (estimate.sufficiency != DataSufficiency.TOO_THIN) return false
        if (predictions.any { it.categoryId == category.id && it.regime == regime }) return false
        if (category.flow != Flow.OUT) return false
        if (category.isSystem || category.archived) return false
        if (regime !in regimesInHorizon) return false
        return true
    }

    /** Every regime that occurs on at least one day in `[today, forecastEnd]`. */
    private fun regimesInRange(today: LocalDate, forecastEnd: LocalDate, terms: List<TermPeriod>): Set<Regime> {
        val result = HashSet<Regime>(2)
        var d = today
        while (!d.isAfter(forecastEnd)) {
            result += Regimes.regimeOf(d, terms)
            if (result.size == Regime.entries.size) break
            d = d.plusDays(1)
        }
        return result
    }

    private fun weightedSum(w: Double, a: Money, b: Money): Money =
        Money(Math.round(w * a.pence + (1 - w) * b.pence))

    internal val UNIFORM_SHARES = List(7) { 1.0 / 7.0 }

    /**
     * Day-of-week shares per learned category, from the last ≤ [ForecastConstants.DOW_WEEKS]
     * complete weeks, using absolute daily amounts. A global shape `g` (learned from every OUT
     * category, blended with a uniform prior) backs off each category's own shape when that
     * category has little or no data of its own.
     */
    private fun dayOfWeekShares(history: History, learned: List<Category>): Map<Long, List<Double>> {
        val recentWeeks = history.weeks.takeLast(ForecastConstants.DOW_WEEKS)
        val n = recentWeeks.size.toDouble()
        val beta = ForecastConstants.DOW_PRIOR_WEEKS
        val outCategories = learned.filter { it.flow == Flow.OUT }

        val globalAbs = DoubleArray(7)
        for (week in recentWeeks) {
            for (dow in 0..6) {
                val date = week.weekStart.plusDays(dow.toLong())
                var sum = 0.0
                for (c in outCategories) sum += abs(history.dayAmount(c.id, date).pence.toDouble())
                globalAbs[dow] += sum
            }
        }
        val globalTotal = globalAbs.sum()
        val g = DoubleArray(7) { 1.0 / 7.0 }
        if (globalTotal > 0.0 && n > 0.0) {
            for (i in 0..6) {
                val eG = globalAbs[i] / globalTotal
                g[i] = (n * eG + beta / 7.0) / (n + beta)
            }
        }

        val result = HashMap<Long, List<Double>>(learned.size)
        for (category in learned) {
            val catAbs = DoubleArray(7)
            var weeksWithData = 0
            for (week in recentWeeks) {
                val perDay = DoubleArray(7)
                var weekTotal = 0.0
                for (dow in 0..6) {
                    val date = week.weekStart.plusDays(dow.toLong())
                    val v = abs(history.dayAmount(category.id, date).pence.toDouble())
                    perDay[dow] = v
                    weekTotal += v
                }
                if (weekTotal > 0.0) {
                    weeksWithData++
                    for (dow in 0..6) catAbs[dow] += perDay[dow]
                }
            }
            val catTotal = catAbs.sum()
            val raw = if (weeksWithData == 0 || catTotal <= 0.0) {
                g.copyOf()
            } else {
                val nC = weeksWithData.toDouble()
                DoubleArray(7) { i -> (nC * (catAbs[i] / catTotal) + beta * g[i]) / (nC + beta) }
            }
            val total = raw.sum()
            result[category.id] = if (total > 0.0) raw.map { it / total } else UNIFORM_SHARES
        }
        return result
    }
}

/** This list's share for [date]'s day of week (Mon=index 0 .. Sun=index 6). */
internal fun List<Double>.forDay(date: LocalDate): Double = this[date.dayOfWeek.value - 1]

/**
 * The expected learned amount for one category on one date: its weekly estimate for that
 * date's regime, times its day-of-week share.
 */
internal fun categoryDayAmount(
    category: Category,
    date: LocalDate,
    terms: List<TermPeriod>,
    estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
    dayOfWeekShares: Map<Long, List<Double>>,
): Money {
    val regime = Regimes.regimeOf(date, terms)
    val weekly = estimateByKey[category.id to regime]?.weeklyAmount ?: Money.ZERO
    val share = dayOfWeekShares[category.id]?.forDay(date) ?: (1.0 / 7.0)
    return Money(Math.round(weekly.pence * share))
}
