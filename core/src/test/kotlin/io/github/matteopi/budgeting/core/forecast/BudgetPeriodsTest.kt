package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BudgetPeriodsTest {
    private val today = LocalDate.of(2024, 6, 3) // Monday

    private fun opening(amount: Money) =
        TestData.tx(TestData.CURRENT, amount, today.minusYears(1), null, kind = TxKind.OPENING_BALANCE)

    private fun compute(input: ForecastInput): List<BudgetPeriod> {
        val forecastEnd = today.plusDays(input.settings.forecastHorizonDays.toLong())
        val committedFlows = CommittedFlows.build(input, forecastEnd)
        val tier2 = Tier2.compute(HistoryBuilder.build(input), input, today, forecastEnd)
        val poolBalanceNow = Balances.poolBalance(input.accounts, input.transactions, today)
        val floor = Balances.floor(input.accounts, input.settings.zeroPoint)
        return BudgetPeriods.compute(input, today, forecastEnd, committedFlows, tier2.estimateByKey, tier2.dayOfWeekShares, poolBalanceNow, floor)
    }

    @Test
    fun noMajorIncomeGivesOneCurrentPeriodCoveringTheWholeHorizon() {
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(500))),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 14),
        )
        val periods = compute(input)
        assertEquals(1, periods.size)
        assertEquals(today.plusDays(1) to today.plusDays(14), periods[0].start to periods[0].end)
        assertEquals(Money.ZERO, periods[0].startingIncome)
        assertEquals(Money.pounds(500), periods[0].availableForSpending)
    }

    @Test
    fun splitsAtAMajorIncomeWithCurrentAndLaterFormulas() {
        val loan = ScheduledItem(
            id = 1, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(2000), date = today.plusDays(8), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val expenseA = ScheduledItem(
            id = 2, label = "A", accountId = TestData.CURRENT, categoryId = TestData.RENT,
            amount = Money.pounds(-100), date = today.plusDays(3), postingMode = PostingMode.AUTO,
        )
        val expenseB = ScheduledItem(
            id = 3, label = "B", accountId = TestData.CURRENT, categoryId = TestData.RENT,
            amount = Money.pounds(-150), date = today.plusDays(20), postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(500))), scheduledItems = listOf(loan, expenseA, expenseB),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 35),
        )
        val periods = compute(input)
        assertEquals(2, periods.size)

        val period0 = periods[0]
        assertEquals(today.plusDays(1) to today.plusDays(7), period0.start to period0.end)
        assertEquals(Money.ZERO, period0.startingIncome)
        assertEquals(Money.pounds(-100), period0.committedOut)
        assertEquals(1.0, period0.weeks)
        // current-period formula: poolNow - reservedToday - floor - buffer + committedOut
        assertEquals(Money.pounds(400), period0.availableForSpending)
        assertEquals(Money.pounds(400), period0.perWeek)

        val period1 = periods[1]
        assertEquals(today.plusDays(8) to today.plusDays(35), period1.start to period1.end)
        assertEquals(Money.pounds(2000), period1.startingIncome)
        assertEquals(Money.pounds(-150), period1.committedOut)
        assertEquals(4.0, period1.weeks)
        // later-period formula: startingIncome + committedOut
        assertEquals(Money.pounds(1850), period1.availableForSpending)
        assertEquals(Money(46250), period1.perWeek) // £1850 / 4 weeks = £462.50
    }

    @Test
    fun sameDayMajorIncomesAreMergedIntoOneBoundary() {
        val loan = ScheduledItem(
            id = 1, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(2000), date = today.plusDays(7), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val bursary = RecurringRule(
            id = 2, label = "Bursary", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(500), frequency = Frequency.YEARLY, startDate = today.plusDays(7),
            postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(500))), scheduledItems = listOf(loan), recurringRules = listOf(bursary),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 21),
        )
        val periods = compute(input)
        assertEquals(2, periods.size)
        assertEquals(today.plusDays(7), periods[1].start)
        assertEquals(Money.pounds(2500), periods[1].startingIncome)
    }

    @Test
    fun fundAllocationReducesAvailableForSpending() {
        val fund = SinkingFund(id = 1, name = "Trip", target = Money.pounds(70), startDate = today.plusDays(1), targetDate = today.plusDays(8))
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(500))), sinkingFunds = listOf(fund),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 7),
        )
        val periods = compute(input)
        val period = periods.single()
        // Fund ramps fully over the 7-day period (start day1..day7): reserved(end)-reserved(start-1) = 70-0 = 70.
        assertEquals(Money.pounds(430), period.availableForSpending)
    }
}
