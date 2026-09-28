package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Tier3Test {
    private val today = LocalDate.of(2024, 6, 3) // Monday

    private fun weeklyHistory(amounts: List<Money>, categoryId: Long = TestData.GROCERIES) =
        amounts.mapIndexed { i, amount ->
            TestData.tx(TestData.CURRENT, amount, today.minusWeeks((amounts.size - i).toLong()).plusDays(2), categoryId)
        }

    @Test
    fun sameSeedGivesTheSameResult() {
        val amounts = listOf(-50, -30, -60, -20, -45, -55, -25, -40).map { Money.pounds(it.toLong()) }
        val historyStart = today.minusWeeks(8)
        val input = TestData.input(
            today, transactions = weeklyHistory(amounts), settings = TestData.settings(historyStartDate = historyStart, simulations = 300, forecastHorizonDays = 60),
            runMonteCarlo = true, seed = 42L,
        )
        val a = ForecastEngine.run(input)
        val b = ForecastEngine.run(input)
        assertEquals(a.days.map { it.p10 }, b.days.map { it.p10 })
        assertEquals(a.days.map { it.p50 }, b.days.map { it.p50 })
        assertEquals(a.days.map { it.p90 }, b.days.map { it.p90 })
        assertEquals(a.risk, b.risk)
    }

    @Test
    fun differentSeedsCanGiveDifferentResults() {
        val amounts = listOf(-50, -30, -60, -20, -45, -55, -25, -40).map { Money.pounds(it.toLong()) }
        val historyStart = today.minusWeeks(8)
        fun run(seed: Long) = ForecastEngine.run(
            TestData.input(
                today, transactions = weeklyHistory(amounts), settings = TestData.settings(historyStartDate = historyStart, simulations = 300, forecastHorizonDays = 60),
                runMonteCarlo = true, seed = seed,
            ),
        )
        val a = run(1L)
        val b = run(2L)
        assertTrue(a.days.map { it.p50 } != b.days.map { it.p50 } || a.risk != b.risk)
    }

    @Test
    fun percentilesAreOrderedP10LeP50LeP90() {
        val amounts = listOf(-50, -30, -60, -20, -45, -55, -25, -40).map { Money.pounds(it.toLong()) }
        val historyStart = today.minusWeeks(8)
        val input = TestData.input(
            today, transactions = weeklyHistory(amounts), settings = TestData.settings(historyStartDate = historyStart, simulations = 300, forecastHorizonDays = 60),
            runMonteCarlo = true,
        )
        val result = ForecastEngine.run(input)
        for (day in result.days) {
            val p10 = day.p10!!
            val p50 = day.p50!!
            val p90 = day.p90!!
            assertTrue(p10 <= p50, "day ${day.date}: p10 (${p10}) should be <= p50 (${p50})")
            assertTrue(p50 <= p90, "day ${day.date}: p50 (${p50}) should be <= p90 (${p90})")
        }
    }

    @Test
    fun zeroVarianceHistoryGivesAPerfectlyNarrowBand() {
        // Every week is identically -£20: no randomness can change the outcome, so the
        // multiplicative bootstrap reproduces the exact same weekly amount every time.
        val amounts = List(8) { Money.pounds(-20) }
        val historyStart = today.minusWeeks(8)
        val input = TestData.input(
            today, transactions = weeklyHistory(amounts), settings = TestData.settings(historyStartDate = historyStart, simulations = 200, forecastHorizonDays = 40),
            runMonteCarlo = true,
        )
        val result = ForecastEngine.run(input)
        for (day in result.days) {
            assertEquals(day.p10, day.p50, "day ${day.date}")
            assertEquals(day.p50, day.p90, "day ${day.date}")
        }
    }

    @Test
    fun outCategorySimulationsAreClampedToNeverGoPositive() {
        // Mostly spending, with one "refund" week thrown in so the multiplicative ratio can
        // legitimately flip sign before clamping.
        val amounts = listOf(-50, -50, -50, 30, -50, -50, -50, -50).map { Money.pounds(it.toLong()) }
        val historyStart = today.minusWeeks(8)
        val input = TestData.input(
            today, transactions = weeklyHistory(amounts), settings = TestData.settings(historyStartDate = historyStart, simulations = 300, forecastHorizonDays = 40),
            runMonteCarlo = true,
        )
        val result = ForecastEngine.run(input)
        // No committed flows and a starting pool balance of 0: any positive p90 would mean an
        // OUT category's simulated spending was left un-clamped.
        for (day in result.days.drop(1)) {
            assertTrue((day.p90?.pence ?: 0) <= 0, "day ${day.date}: p90 was ${day.p90}")
        }
    }

    @Test
    fun inCategorySimulationsAreClampedToNeverGoNegative() {
        val amounts = listOf(50, 50, 50, -30, 50, 50, 50, 50).map { Money.pounds(it.toLong()) }
        val historyStart = today.minusWeeks(8)
        val input = TestData.input(
            today, transactions = weeklyHistory(amounts, categoryId = TestData.WAGES),
            settings = TestData.settings(historyStartDate = historyStart, simulations = 300, forecastHorizonDays = 40),
            runMonteCarlo = true,
        )
        val result = ForecastEngine.run(input)
        for (day in result.days.drop(1)) {
            assertTrue((day.p10?.pence ?: 0) >= 0, "day ${day.date}: p10 was ${day.p10}")
        }
    }

    @Test
    fun riskIsNullWhenMonteCarloIsSkipped() {
        val input = TestData.input(today, runMonteCarlo = false)
        val result = ForecastEngine.run(input)
        assertEquals(null, result.risk)
        assertTrue(result.days.all { it.p10 == null && it.p50 == null && it.p90 == null })
    }

    @Test
    fun riskProbabilitiesAreValidShares() {
        val input = TestData.input(today, settings = TestData.settings(simulations = 200, forecastHorizonDays = 40), runMonteCarlo = true)
        val risk = ForecastEngine.run(input).risk!!
        assertTrue(risk.probBelowFloor in 0.0..1.0)
        assertTrue(risk.probBeyondOverdraft in 0.0..1.0)
        assertTrue(risk.probBeyondOverdraft <= risk.probBelowFloor + 1e-9)
    }
}
