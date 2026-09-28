package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Regime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class Tier1Test {
    private val today = LocalDate.of(2024, 6, 3) // Monday

    @Test
    fun medianOfUpToEightMostRecentWeeks() {
        // 10 weeks of history; only the most recent 8 should count. The two oldest weeks
        // (£100, £90) are chosen far outside the other values so a wrong median would show.
        val amounts = listOf(100, 90, 10, 12, 14, 16, 18, 20, 22, 24).map { Money.pounds(-it.toLong()) }
        val historyStart = today.minusWeeks(10)
        val txs = amounts.mapIndexed { i, amount ->
            TestData.tx(TestData.CURRENT, amount, historyStart.plusWeeks(i.toLong()).plusDays(2), TestData.GROCERIES)
        }
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val history = HistoryBuilder.build(input)
        val weekly = Tier1.weeklyEstimates(history, input)
        // Most recent 8 weeks: -10..-24 in steps of -2; median = avg(-16,-18) = -17.
        assertEquals(Money.pounds(-17), weekly[TestData.GROCERIES])
    }

    @Test
    fun fallsBackToAverageOfPriorAndReplacePredictionsWhenThereIsNoHistory() {
        val predictions = listOf(
            Prediction(id = 1, categoryId = TestData.GROCERIES, regime = Regime.TERM, mode = PredictionMode.PRIOR, weeklyAmount = Money.pounds(40)),
            Prediction(id = 2, categoryId = TestData.GROCERIES, regime = Regime.HOLIDAY, mode = PredictionMode.REPLACE, weeklyAmount = Money.pounds(20)),
        )
        val input = TestData.input(today, predictions = predictions)
        val weekly = Tier1.weeklyEstimates(HistoryBuilder.build(input), input)
        // Groceries is OUT: signed -40 and -20, averaged -> -30.
        assertEquals(Money.pounds(-30), weekly[TestData.GROCERIES])
    }

    @Test
    fun adjustPercentPredictionsAreIgnoredByTheFallback() {
        val predictions = listOf(
            Prediction(id = 1, categoryId = TestData.GROCERIES, regime = Regime.TERM, mode = PredictionMode.ADJUST_PERCENT, adjustPercent = -50),
        )
        val input = TestData.input(today, predictions = predictions)
        val weekly = Tier1.weeklyEstimates(HistoryBuilder.build(input), input)
        assertEquals(Money.ZERO, weekly[TestData.GROCERIES])
    }

    @Test
    fun zeroWhenThereIsNoHistoryAndNoPredictions() {
        val input = TestData.input(today)
        val weekly = Tier1.weeklyEstimates(HistoryBuilder.build(input), input)
        assertEquals(Money.ZERO, weekly[TestData.GROCERIES])
    }

    @Test
    fun dailyTotalIsTheSumOfWeeklyEstimatesDividedBySeven() {
        val weekly = mapOf(TestData.GROCERIES to Money.pounds(-70), TestData.EATING_OUT to Money.pounds(-35))
        // -7000/7 + -3500/7 = -1000 + -500 = -1500 pence = -£15.
        assertEquals(Money.pounds(-15), Tier1.dailyTotal(weekly))
    }
}
