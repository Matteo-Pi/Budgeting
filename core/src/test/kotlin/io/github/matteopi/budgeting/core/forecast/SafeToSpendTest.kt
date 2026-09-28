package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeToSpendTest {
    private val today = LocalDate.of(2024, 6, 3) // Monday

    private fun opening(amount: Money) =
        TestData.tx(TestData.CURRENT, amount, today.minusYears(1), null, kind = TxKind.OPENING_BALANCE)

    private fun compute(input: ForecastInput): SafeToSpend {
        val forecastEnd = today.plusDays(input.settings.forecastHorizonDays.toLong())
        val committedFlows = CommittedFlows.build(input, forecastEnd)
        val tier2 = Tier2.compute(HistoryBuilder.build(input), input, today, forecastEnd)
        val poolBalanceNow = Balances.poolBalance(input.accounts, input.transactions, today)
        val floor = Balances.floor(input.accounts, input.settings.zeroPoint)
        return SafeToSpendCalculator.compute(input, today, forecastEnd, committedFlows, tier2.estimateByKey, tier2.dayOfWeekShares, poolBalanceNow, floor)
    }

    @Test
    fun bindingDateAndReasonPickUpTheRentDueJustBeforeTheLoan() {
        // Loan arrives on day+20 (>= 7 days away, so the horizon isn't pushed further out).
        // Rent lands right on the horizon end (day+19, the day before the loan): the answer is
        // limited by rent, not simply "wait for the loan".
        val loan = ScheduledItem(
            id = 1, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(3000), date = today.plusDays(20), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val rent = ScheduledItem(
            id = 2, label = "Rent & bills", accountId = TestData.CURRENT, categoryId = TestData.RENT,
            amount = Money.pounds(-300), date = today.plusDays(19), postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))), scheduledItems = listOf(loan, rent),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 60),
        )
        val result = compute(input)
        assertEquals(today.plusDays(19), result.horizonEnd)
        assertEquals(today.plusDays(19), result.bindingDate)
        assertEquals("Rent & bills on ${formatted(today.plusDays(19))}", result.bindingReason)
    }

    @Test
    fun fallsBackToUntilTheMajorIncomeWhenNoOutflowExplainsTheBindingDay() {
        val loan = ScheduledItem(
            id = 1, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(3000), date = today.plusDays(20), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))), scheduledItems = listOf(loan),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 60),
        )
        val result = compute(input)
        assertEquals(today.plusDays(19), result.horizonEnd)
        assertEquals(today.plusDays(19), result.bindingDate)
        assertEquals("Until Student loan on ${formatted(today.plusDays(20))}", result.bindingReason)
    }

    @Test
    fun shortfallIsPositiveWhenEvenZeroSpendingBreachesFloorPlusBuffer() {
        val rent = ScheduledItem(
            id = 1, label = "Rent", accountId = TestData.CURRENT, categoryId = TestData.RENT,
            amount = Money.pounds(-300), date = today.plusDays(5), postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(100))), scheduledItems = listOf(rent),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 30),
        )
        val result = compute(input)
        assertEquals(Money.pounds(200), result.shortfall)
        assertEquals(Money.ZERO, result.weekly)
    }

    @Test
    fun lessThanSevenDaysAwayPushesTheHorizonToTheFollowingMajorIncome() {
        val soon = ScheduledItem(
            id = 1, label = "Small refund", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(50), date = today.plusDays(3), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val later = ScheduledItem(
            id = 2, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(3000), date = today.plusDays(40), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))), scheduledItems = listOf(soon, later),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 90),
        )
        val result = compute(input)
        // nextMajorIncome is still the true next one (3 days away)...
        assertEquals(today.plusDays(3), result.nextMajorIncome?.date)
        assertEquals(3, result.daysUntilNextMajorIncome)
        // ...but because it is less than 7 days away, the horizon looks past it to the next one.
        assertEquals(today.plusDays(39), result.horizonEnd)
    }

    @Test
    fun sevenOrMoreDaysAwayUsesThatIncomeDirectly() {
        val loan = ScheduledItem(
            id = 1, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(3000), date = today.plusDays(7), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))), scheduledItems = listOf(loan),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 60),
        )
        val result = compute(input)
        assertEquals(today.plusDays(6), result.horizonEnd)
    }

    @Test
    fun noMajorIncomeAtAllUsesTheForecastEnd() {
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 30),
        )
        val result = compute(input)
        assertEquals(today.plusDays(30), result.horizonEnd)
        assertNull(result.nextMajorIncome)
        assertNull(result.daysUntilNextMajorIncome)
    }

    @Test
    fun bufferReducesWeeklySafeToSpend() {
        val noBuffer = compute(
            TestData.input(
                today, transactions = listOf(opening(Money.pounds(1000))),
                settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 14),
            ),
        )
        val withBuffer = compute(
            TestData.input(
                today, transactions = listOf(opening(Money.pounds(1000))),
                settings = TestData.settings(buffer = Money.pounds(100), forecastHorizonDays = 14),
            ),
        )
        // No flows at all: h is constant, so weekly = floor(7h/14) = h/2.
        assertEquals(Money.pounds(500), noBuffer.weekly)
        assertEquals(Money.pounds(450), withBuffer.weekly)
    }

    @Test
    fun overdraftLimitZeroPointGivesMoreHeadroomThanAccountBalance() {
        val accountBalanceMode = compute(
            TestData.input(
                today, transactions = listOf(opening(Money.pounds(1000))),
                settings = TestData.settings(zeroPoint = ZeroPoint.ACCOUNT_BALANCE, buffer = Money.ZERO, forecastHorizonDays = 14),
            ),
        )
        val overdraftMode = compute(
            TestData.input(
                today, transactions = listOf(opening(Money.pounds(1000))),
                settings = TestData.settings(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT, buffer = Money.ZERO, forecastHorizonDays = 14),
            ),
        )
        // TestData's CURRENT account has a £200 overdraft: floor = -£200, so headroom is £200 higher.
        assertEquals(Money.pounds(500), accountBalanceMode.weekly)
        assertEquals(Money.pounds(600), overdraftMode.weekly)
        assertTrue(overdraftMode.weekly > accountBalanceMode.weekly)
    }

    @Test
    fun septemberIsAbbreviatedAsSepNotSept() {
        // en-GB CLDR data says "Sept"; the app shows the standard three-letter form everywhere.
        val augustMonday = LocalDate.of(2026, 8, 24)
        val loan = ScheduledItem(
            id = 1, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(3000), date = augustMonday.plusDays(20), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val rent = ScheduledItem(
            id = 2, label = "Rent & bills", accountId = TestData.CURRENT, categoryId = TestData.RENT,
            amount = Money.pounds(-300), date = augustMonday.plusDays(19), postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(
            augustMonday,
            transactions = listOf(TestData.tx(TestData.CURRENT, Money.pounds(1000), augustMonday.minusYears(1), null, kind = TxKind.OPENING_BALANCE)),
            scheduledItems = listOf(loan, rent),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 60),
        )
        assertEquals("Rent & bills on 12 Sep", ForecastEngine.run(input).safeToSpend.bindingReason)
    }

    private fun formatted(date: LocalDate): String {
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "${date.dayOfMonth} ${months[date.monthValue - 1]}"
    }
}
