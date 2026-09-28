package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SystemKeys
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastEngineTest {
    private val today = LocalDate.of(2024, 6, 3) // Monday

    @Test
    fun daysCoverTodayThroughForecastEndInclusive() {
        val input = TestData.input(today, settings = TestData.settings(forecastHorizonDays = 20), runMonteCarlo = false)
        val result = ForecastEngine.run(input)
        assertEquals(today, result.days.first().date)
        assertEquals(today.plusDays(20), result.days.last().date)
        assertEquals(21, result.days.size)
        assertEquals(today.plusDays(20), result.forecastEnd)
    }

    @Test
    fun todaysEntryHasNoFlowsAndTheStartingBalance() {
        val opening = TestData.tx(TestData.CURRENT, Money.pounds(500), today.minusYears(1), null, kind = TxKind.OPENING_BALANCE)
        val input = TestData.input(today, transactions = listOf(opening), runMonteCarlo = false)
        val result = ForecastEngine.run(input)
        val first = result.days.first()
        assertEquals(Money.pounds(500), result.poolBalanceNow)
        assertEquals(Money.ZERO, first.committedNet)
        assertEquals(Money.ZERO, first.expectedLearned)
        assertEquals(Money.pounds(500), first.tier1Balance)
        assertEquals(Money.pounds(500), first.expectedBalance)
    }

    @Test
    fun percentilesAreNullWhenMonteCarloIsSkippedAndPresentOtherwise() {
        val skipped = ForecastEngine.run(TestData.input(today, runMonteCarlo = false))
        assertTrue(skipped.days.all { it.p10 == null && it.p50 == null && it.p90 == null })
        assertNull(skipped.risk)

        val withMc = ForecastEngine.run(TestData.input(today, settings = TestData.settings(forecastHorizonDays = 20, simulations = 100), runMonteCarlo = true))
        assertTrue(withMc.days.all { it.p10 != null && it.p50 != null && it.p90 != null })
        assertNotNull(withMc.risk)
    }

    @Test
    fun deterministicForEqualInput() {
        val opening = TestData.tx(TestData.CURRENT, Money.pounds(750), today.minusYears(1), null, kind = TxKind.OPENING_BALANCE)
        val rule = RecurringRule(
            id = 1, label = "Rent", accountId = TestData.CURRENT, categoryId = TestData.RENT, amount = Money.pounds(-400),
            frequency = Frequency.MONTHLY, startDate = today.plusDays(5), postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(
            today, transactions = listOf(opening), recurringRules = listOf(rule),
            settings = TestData.settings(forecastHorizonDays = 40, simulations = 150), runMonteCarlo = true,
        )
        val a = ForecastEngine.run(input)
        val b = ForecastEngine.run(input)
        assertEquals(a, b)
    }

    @Test
    fun tier1WeeklyByCategoryCoversEveryLearnedCategory() {
        val result = ForecastEngine.run(TestData.input(today, runMonteCarlo = false))
        val learnedIds = TestData.categories().filter { it.forecastMode == ForecastMode.LEARNED }.map { it.id }.toSet()
        assertEquals(learnedIds, result.tier1WeeklyByCategory.keys)
    }

    @Test
    fun categoryEstimatesHaveOneEntryPerLearnedCategoryAndRegime() {
        val result = ForecastEngine.run(TestData.input(today, runMonteCarlo = false))
        val learnedCount = TestData.categories().count { it.forecastMode == ForecastMode.LEARNED }
        assertEquals(learnedCount * 2, result.categoryEstimates.size)
    }

    @Test
    fun noHistoryAndNoHistoryStartDateGivesNullHistoryStart() {
        val result = ForecastEngine.run(TestData.input(today, runMonteCarlo = false))
        assertNull(result.historyStart)
        assertEquals(0, result.completeWeeksOfHistory)
    }

    /**
     * A realistic-sized run: 1000 simulations, a full 183-day horizon, ~15 learned categories
     * with real (varied) history, plus recurring rules, scheduled items and a sinking fund.
     * Generous 2 s budget to avoid CI flakiness; the actual runtime should be far below that.
     */
    @Test
    fun realisticRunFinishesWellUnderTwoSeconds() {
        val learned = (1..14).map { i ->
            Category(
                id = 100L + i, name = "Category $i",
                flow = if (i % 5 == 0) Flow.IN else Flow.OUT, forecastMode = ForecastMode.LEARNED,
            )
        }
        val untracked = Category(
            id = TestData.UNTRACKED, name = "Untracked", flow = Flow.BOTH, forecastMode = ForecastMode.LEARNED,
            isSystem = true, systemKey = SystemKeys.UNTRACKED,
        )
        val categories = learned + untracked + listOf(
            Category(id = TestData.RENT, name = "Rent & bills", flow = Flow.OUT, forecastMode = ForecastMode.SCHEDULED_ONLY),
            Category(
                id = TestData.STUDENT_LOAN, name = "Student loan", flow = Flow.IN, forecastMode = ForecastMode.SCHEDULED_ONLY,
                isMajorIncomeDefault = true,
            ),
        )

        val historyStart = today.minusWeeks(12)
        val txs = mutableListOf<Transaction>()
        for (category in learned) {
            for (w in 0 until 12) {
                val magnitude = 10 + ((category.id + w) % 15)
                val amount = if (category.flow == Flow.IN) Money.pounds(magnitude) else Money.pounds(-magnitude)
                txs += TestData.tx(TestData.CURRENT, amount, historyStart.plusWeeks(w.toLong()).plusDays((w % 7).toLong()), category.id)
            }
        }

        val opening = TestData.tx(TestData.CURRENT, Money.pounds(1200), today.minusYears(2), null, kind = TxKind.OPENING_BALANCE)
        val rent = RecurringRule(
            id = 1, label = "Rent", accountId = TestData.CURRENT, categoryId = TestData.RENT, amount = Money.pounds(-500),
            frequency = Frequency.MONTHLY, startDate = today.minusMonths(3), postingMode = PostingMode.AUTO,
        )
        val loan = RecurringRule(
            id = 2, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN, amount = Money.pounds(3000),
            frequency = Frequency.MONTHLY, interval = 4, startDate = today.plusDays(20), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val oneOff = ScheduledItem(
            id = 1, label = "Textbooks", accountId = TestData.CURRENT, categoryId = TestData.RENT, amount = Money.pounds(-80),
            date = today.plusDays(10), postingMode = PostingMode.AUTO,
        )
        val fund = SinkingFund(id = 1, name = "Trip", target = Money.pounds(600), startDate = today.minusDays(10), targetDate = today.plusDays(90))

        val input = TestData.input(
            today, categories = categories, transactions = txs + opening,
            recurringRules = listOf(rent, loan), scheduledItems = listOf(oneOff), sinkingFunds = listOf(fund),
            settings = TestData.settings(historyStartDate = historyStart, simulations = 1000, forecastHorizonDays = 183),
            runMonteCarlo = true,
        )

        val start = System.nanoTime()
        val result = ForecastEngine.run(input)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(elapsedMs < 2000, "expected the run to finish in under 2000ms, took ${elapsedMs}ms")
        assertEquals(184, result.days.size)
        assertNotNull(result.risk)
    }
}
