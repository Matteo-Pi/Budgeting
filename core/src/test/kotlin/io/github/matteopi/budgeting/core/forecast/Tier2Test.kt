package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Tier2Test {
    private val today = LocalDate.of(2024, 6, 3) // Monday
    private val forecastEnd = today.plusDays(182)

    private fun compute(input: ForecastInput): Tier2.Result =
        Tier2.compute(HistoryBuilder.build(input), input, today, forecastEnd)

    @Test
    fun shrinksTowardTheAllRegimeFallbackWhenThereIsLittleRegimeData() {
        // 2 TERM weeks at -£20 each, 10 HOLIDAY weeks at -£5 each: 12 weeks total.
        val historyStart = today.minusWeeks(12)
        val term = TermPeriod(id = 1, name = "T", startDate = historyStart, endDate = historyStart.plusDays(13))
        val txs = (0 until 12).map { i ->
            val amount = if (i < 2) Money.pounds(-20) else Money.pounds(-5)
            TestData.tx(TestData.CURRENT, amount, historyStart.plusWeeks(i.toLong()).plusDays(2), TestData.GROCERIES)
        }
        val input = TestData.input(today, transactions = txs, termPeriods = listOf(term), settings = TestData.settings(historyStartDate = historyStart))
        val estimate = compute(input).estimateByKey[TestData.GROCERIES to Regime.TERM]!!
        // n_r=2, m_r=-2000p; n_all=12, m_all=-500p (F); w=2/6=1/3.
        // A = (1/3)*(-2000) + (2/3)*(-500) = -1000p exactly.
        assertEquals(2, estimate.weeksOfData)
        assertEquals(DataSufficiency.THIN, estimate.sufficiency)
        assertEquals(EstimateSource.BLENDED, estimate.source)
        assertEquals(Money.pounds(-10), estimate.weeklyAmount)
        assertEquals(Money.pounds(-10), estimate.automaticWeeklyAmount)
    }

    @Test
    fun sufficiencyIsTrustedAtSixOrMoreWeeksWithSourceData() {
        val historyStart = today.minusWeeks(6)
        val term = TermPeriod(id = 1, name = "T", startDate = historyStart, endDate = historyStart.plusWeeks(6).minusDays(1))
        val txs = (0 until 6).map { i -> TestData.tx(TestData.CURRENT, Money.pounds(-10), historyStart.plusWeeks(i.toLong()).plusDays(2), TestData.GROCERIES) }
        val input = TestData.input(today, transactions = txs, termPeriods = listOf(term), settings = TestData.settings(historyStartDate = historyStart))
        val estimate = compute(input).estimateByKey[TestData.GROCERIES to Regime.TERM]!!
        assertEquals(6, estimate.weeksOfData)
        assertEquals(DataSufficiency.TRUSTED, estimate.sufficiency)
        assertEquals(EstimateSource.DATA, estimate.source)
        assertEquals(Money.pounds(-10), estimate.weeklyAmount)
    }

    @Test
    fun fallbackIsUnavailableBelowFourRecentWeeksSoTheEstimateIsZero() {
        val historyStart = today.minusWeeks(3)
        val txs = (0 until 3).map { i -> TestData.tx(TestData.CURRENT, Money.pounds(-8), historyStart.plusWeeks(i.toLong()).plusDays(2), TestData.GROCERIES) }
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val estimate = compute(input).estimateByKey[TestData.GROCERIES to Regime.TERM]!!
        assertEquals(0, estimate.weeksOfData)
        assertEquals(DataSufficiency.TOO_THIN, estimate.sufficiency)
        assertEquals(EstimateSource.NONE, estimate.source)
        assertEquals(Money.ZERO, estimate.weeklyAmount)
    }

    @Test
    fun fallbackAppliesFromFourRecentWeeksOnwards() {
        val historyStart = today.minusWeeks(4)
        val txs = (0 until 4).map { i -> TestData.tx(TestData.CURRENT, Money.pounds(-8), historyStart.plusWeeks(i.toLong()).plusDays(2), TestData.GROCERIES) }
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val estimate = compute(input).estimateByKey[TestData.GROCERIES to Regime.TERM]!!
        assertEquals(0, estimate.weeksOfData) // no TERM weeks: this is the all-regime fallback
        assertEquals(EstimateSource.FALLBACK_OVERALL, estimate.source)
        assertEquals(Money.pounds(-8), estimate.weeklyAmount)
    }

    @Test
    fun replacePredictionOverridesEverythingRegardlessOfData() {
        val prediction = Prediction(id = 1, categoryId = TestData.GROCERIES, regime = Regime.TERM, mode = PredictionMode.REPLACE, weeklyAmount = Money.pounds(25))
        val estimate = compute(TestData.input(today, predictions = listOf(prediction))).estimateByKey[TestData.GROCERIES to Regime.TERM]!!
        assertEquals(EstimateSource.PREDICTION_REPLACE, estimate.source)
        assertEquals(Money.pounds(-25), estimate.weeklyAmount) // OUT category -> negative
    }

    @Test
    fun adjustPercentScalesTheAutomaticEstimate() {
        val historyStart = today.minusWeeks(4)
        val txs = (0 until 4).map { i -> TestData.tx(TestData.CURRENT, Money.pounds(-8), historyStart.plusWeeks(i.toLong()).plusDays(2), TestData.GROCERIES) }
        val prediction = Prediction(id = 1, categoryId = TestData.GROCERIES, regime = Regime.TERM, mode = PredictionMode.ADJUST_PERCENT, adjustPercent = -50)
        val input = TestData.input(today, transactions = txs, predictions = listOf(prediction), settings = TestData.settings(historyStartDate = historyStart))
        val estimate = compute(input).estimateByKey[TestData.GROCERIES to Regime.TERM]!!
        assertEquals(EstimateSource.PREDICTION_ADJUSTED, estimate.source)
        assertEquals(Money.pounds(-8), estimate.automaticWeeklyAmount)
        assertEquals(Money.pounds(-4), estimate.weeklyAmount)
    }

    @Test
    fun priorBlendsWithDataWhenThereIsSome() {
        val historyStart = today.minusWeeks(2)
        val term = TermPeriod(id = 1, name = "T", startDate = historyStart, endDate = historyStart.plusDays(13))
        val txs = (0 until 2).map { i -> TestData.tx(TestData.CURRENT, Money.pounds(-20), historyStart.plusWeeks(i.toLong()).plusDays(2), TestData.GROCERIES) }
        val prediction = Prediction(id = 1, categoryId = TestData.GROCERIES, regime = Regime.TERM, mode = PredictionMode.PRIOR, weeklyAmount = Money.pounds(10))
        val input = TestData.input(today, transactions = txs, termPeriods = listOf(term), predictions = listOf(prediction), settings = TestData.settings(historyStartDate = historyStart))
        val estimate = compute(input).estimateByKey[TestData.GROCERIES to Regime.TERM]!!
        // n_r=2, w=1/3; signed(P)=-1000p; blend = (1/3)*(-2000)+(2/3)*(-1000) = -1333.33 -> -1333p.
        assertEquals(EstimateSource.BLENDED, estimate.source)
        assertEquals(Money(-1333), estimate.weeklyAmount)
    }

    @Test
    fun priorIsUsedDirectlyWhenThereIsNoDataInTheRegime() {
        val prediction = Prediction(id = 1, categoryId = TestData.GROCERIES, regime = Regime.TERM, mode = PredictionMode.PRIOR, weeklyAmount = Money.pounds(12))
        val estimate = compute(TestData.input(today, predictions = listOf(prediction))).estimateByKey[TestData.GROCERIES to Regime.TERM]!!
        assertEquals(EstimateSource.PREDICTION_PRIOR, estimate.source)
        assertEquals(Money.pounds(-12), estimate.weeklyAmount)
    }

    @Test
    fun needsPredictionFiresForATooThinOutCategoryWithNoPredictionWhenTheRegimeOccurs() {
        // No history at all -> TOO_THIN everywhere; no term periods -> HOLIDAY occurs in the horizon.
        val result = compute(TestData.input(today))
        assertTrue(result.needsPrediction.any { it.categoryId == TestData.GROCERIES && it.regime == Regime.HOLIDAY })
        val request = result.needsPrediction.first { it.categoryId == TestData.GROCERIES && it.regime == Regime.HOLIDAY }
        assertEquals(null, request.suggestedWeekly) // source is NONE (no fallback available)
    }

    @Test
    fun needsPredictionDoesNotFireWhenTheRegimeNeverOccursInTheHorizon() {
        // No term periods anywhere -> TERM never occurs.
        val result = compute(TestData.input(today))
        assertTrue(result.needsPrediction.none { it.categoryId == TestData.GROCERIES && it.regime == Regime.TERM })
    }

    @Test
    fun needsPredictionDoesNotFireWhenAPredictionAlreadyExists() {
        val prediction = Prediction(id = 1, categoryId = TestData.GROCERIES, regime = Regime.HOLIDAY, mode = PredictionMode.PRIOR, weeklyAmount = Money.pounds(5))
        val result = compute(TestData.input(today, predictions = listOf(prediction)))
        assertTrue(result.needsPrediction.none { it.categoryId == TestData.GROCERIES && it.regime == Regime.HOLIDAY })
    }

    @Test
    fun needsPredictionDoesNotFireForInOrSystemCategories() {
        val result = compute(TestData.input(today))
        assertTrue(result.needsPrediction.none { it.categoryId == TestData.WAGES }) // IN
        assertTrue(result.needsPrediction.none { it.categoryId == TestData.UNTRACKED }) // system
    }

    @Test
    fun needsPredictionDoesNotFireWithEnoughData() {
        val historyStart = today.minusWeeks(6)
        val txs = (0 until 6).map { i -> TestData.tx(TestData.CURRENT, Money.pounds(-10), historyStart.plusWeeks(i.toLong()).plusDays(2), TestData.GROCERIES) }
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val result = compute(input)
        assertTrue(result.needsPrediction.none { it.categoryId == TestData.GROCERIES && it.regime == Regime.HOLIDAY })
    }

    @Test
    fun dayOfWeekSharesSumToOneForEveryLearnedCategory() {
        val historyStart = today.minusWeeks(4)
        val txs = (0 until 4).flatMap { i ->
            val monday = historyStart.plusWeeks(i.toLong())
            listOf(
                TestData.tx(TestData.CURRENT, Money.pounds(-3), monday, TestData.GROCERIES),
                TestData.tx(TestData.CURRENT, Money.pounds(-7), monday.plusDays(5), TestData.GROCERIES),
            )
        }
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val result = compute(input)
        for (categoryId in listOf(TestData.GROCERIES, TestData.EATING_OUT, TestData.WAGES, TestData.UNTRACKED)) {
            val shares = result.dayOfWeekShares[categoryId]!!
            assertEquals(7, shares.size)
            assertEquals(1.0, shares.sum(), 1e-9)
            assertTrue(shares.all { it >= 0.0 })
        }
    }

    @Test
    fun dayOfWeekSharesLeanTowardTheDaysTheCategoryIsActuallySpentOn() {
        val historyStart = today.minusWeeks(6)
        // All spending happens on the Saturday (offset 5) of every week.
        val txs = (0 until 6).map { i -> TestData.tx(TestData.CURRENT, Money.pounds(-10), historyStart.plusWeeks(i.toLong()).plusDays(5), TestData.GROCERIES) }
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val shares = compute(input).dayOfWeekShares[TestData.GROCERIES]!!
        val saturdayShare = shares[5]
        assertTrue(saturdayShare > shares[0]) // clearly higher than an untouched day (Monday)
    }
}
