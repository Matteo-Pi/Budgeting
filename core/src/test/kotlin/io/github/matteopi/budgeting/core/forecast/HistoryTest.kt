package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private val today = LocalDate.of(2024, 6, 3) // Monday

    @Test
    fun partialFirstWeekIsExcluded() {
        // History starts on a Wednesday: that first Mon-Sun week is partial and excluded.
        val historyStart = LocalDate.of(2024, 5, 1) // Wednesday
        val txs = listOf(
            TestData.tx(TestData.CURRENT, Money.pounds(-10), LocalDate.of(2024, 5, 2), TestData.GROCERIES), // in the partial week
            TestData.tx(TestData.CURRENT, Money.pounds(-20), LocalDate.of(2024, 5, 6), TestData.GROCERIES), // first complete week (Mon 6 May)
        )
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val history = HistoryBuilder.build(input)
        assertEquals(LocalDate.of(2024, 5, 6), history.weeks.first().weekStart)
        assertEquals(Money.pounds(-20), history.weeklyByCategory[TestData.GROCERIES]!!.first())
    }

    @Test
    fun theCurrentInProgressWeekIsNeverIncluded() {
        val historyStart = today.minusWeeks(3)
        val txs = listOf(TestData.tx(TestData.CURRENT, Money.pounds(-5), today, TestData.GROCERIES))
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val history = HistoryBuilder.build(input)
        assertEquals(today.minusWeeks(1), history.weeks.last().weekStart)
    }

    @Test
    fun noLearnedTransactionsAndNoHistoryStartGivesNoHistory() {
        val history = HistoryBuilder.build(TestData.input(today))
        assertNull(history.historyStart)
        assertTrue(history.weeks.isEmpty())
    }

    @Test
    fun fewerThanTwoWeeksOfHistoryGivesNoCompleteWeeks() {
        val historyStart = today.minusDays(3) // not enough elapsed time for even one complete week
        val input = TestData.input(today, settings = TestData.settings(historyStartDate = historyStart))
        val history = HistoryBuilder.build(input)
        assertEquals(historyStart, history.historyStart)
        assertTrue(history.weeks.isEmpty())
    }

    @Test
    fun excludesTransactionsPostedFromRulesOrItems() {
        val historyStart = today.minusWeeks(4)
        val txs = listOf(
            TestData.tx(TestData.CURRENT, Money.pounds(-30), historyStart.plusDays(1), TestData.GROCERIES).copy(recurringRuleId = 5),
            TestData.tx(TestData.CURRENT, Money.pounds(-15), historyStart.plusDays(2), TestData.GROCERIES),
        )
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val history = HistoryBuilder.build(input)
        val total = history.weeklyByCategory[TestData.GROCERIES]!!.sumOf { it.pence }
        assertEquals(-1500L, total)
    }

    @Test
    fun excludesTransactionsOutsideThePool() {
        val historyStart = today.minusWeeks(4)
        val txs = listOf(TestData.tx(TestData.OUTSIDE_POOL, Money.pounds(-30), historyStart.plusDays(1), TestData.GROCERIES))
        val input = TestData.input(today, transactions = txs, settings = TestData.settings(historyStartDate = historyStart))
        val history = HistoryBuilder.build(input)
        assertEquals(0L, history.weeklyByCategory[TestData.GROCERIES]!!.sumOf { it.pence })
    }

    @Test
    fun reconciliationSpreadsAcrossItsMatchedPeriod() {
        val historyStart = today.minusWeeks(4)
        val reconTx = TestData.tx(TestData.CURRENT, Money(-10), historyStart.plusDays(4), TestData.UNTRACKED, kind = TxKind.RECONCILIATION, id = 500)
        val reconciliation = Reconciliation(
            id = 1, accountId = TestData.CURRENT, date = reconTx.date, expected = Money.ZERO, actual = Money(-10),
            transactionId = 500, periodStart = historyStart.plusDays(1),
        )
        val input = TestData.input(
            today, transactions = listOf(reconTx), reconciliations = listOf(reconciliation),
            settings = TestData.settings(historyStartDate = historyStart),
        )
        val history = HistoryBuilder.build(input)
        // -10 pence over a 4-day period (periodStart..date inclusive): base = -10/4 = -2 (truncating),
        // remainder = -10 - (-2*4) = -2, added to the last day -> -2,-2,-2,-4.
        assertEquals(Money(-2), history.dayAmount(TestData.UNTRACKED, historyStart.plusDays(1)))
        assertEquals(Money(-2), history.dayAmount(TestData.UNTRACKED, historyStart.plusDays(2)))
        assertEquals(Money(-2), history.dayAmount(TestData.UNTRACKED, historyStart.plusDays(3)))
        assertEquals(Money(-4), history.dayAmount(TestData.UNTRACKED, historyStart.plusDays(4)))
        assertEquals(-10L, history.weeklyByCategory[TestData.UNTRACKED]!!.sumOf { it.pence })
    }

    @Test
    fun unmatchedReconciliationSpreadsOverSevenDaysEndingOnItsDate() {
        val historyStart = today.minusWeeks(6)
        val date = historyStart.plusWeeks(1)
        val reconTx = TestData.tx(TestData.CURRENT, Money(-700), date, TestData.UNTRACKED, kind = TxKind.RECONCILIATION, id = 600)
        val input = TestData.input(
            today, transactions = listOf(reconTx),
            settings = TestData.settings(historyStartDate = historyStart),
        )
        val history = HistoryBuilder.build(input)
        for (i in 0..6) {
            assertEquals(Money(-100), history.dayAmount(TestData.UNTRACKED, date.minusDays((6 - i).toLong())))
        }
    }
}
