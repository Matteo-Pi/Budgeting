package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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

    private fun scheduled(
        id: Long,
        label: String,
        pounds: Long,
        dayOffset: Int,
        major: Boolean = false,
        mode: PostingMode = if (pounds > 0) PostingMode.CONFIRM else PostingMode.AUTO,
        category: Long = if (pounds > 0) TestData.STUDENT_LOAN else TestData.RENT,
    ) = ScheduledItem(
        id = id, label = label, accountId = TestData.CURRENT, categoryId = category,
        amount = Money.pounds(pounds), date = today.plusDays(dayOffset.toLong()), postingMode = mode, isMajorIncome = major,
    )

    /**
     * Independent reference for flow-only inputs (no learned history, no funds): the tightest
     * `floor(7 * h(k) / k)` over k = 1..[horizonDays], with `h(k) = pool + flows up to day k - buffer`.
     * [flows] are (day offset from today, pence).
     */
    private fun referenceWeekly(poolPence: Long, bufferPence: Long, horizonDays: Int, flows: List<Pair<Int, Long>>): Money {
        var best = Long.MAX_VALUE
        for (k in 1..horizonDays) {
            val h = poolPence + flows.filter { it.first <= k }.sumOf { it.second } - bufferPence
            best = minOf(best, Math.floorDiv(7 * h, k.toLong()))
        }
        return Money(maxOf(0L, best))
    }

    // --- binding reasons -------------------------------------------------------------------

    @Test
    fun bindingReasonNamesTheRentDueJustBeforeTheLoan() {
        // Rent lands on day+19, the loan on day+20: h is tightest on the day of the rent (the day
        // before the loan), and the rent is what put it there.
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))),
            scheduledItems = listOf(
                scheduled(1, "Student loan", 3000, 20, major = true),
                scheduled(2, "Rent & bills", -300, 19),
            ),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 60),
        )
        val result = compute(input)
        assertEquals(today.plusDays(60), result.horizonEnd)
        assertEquals(today.plusDays(19), result.bindingDate)
        assertEquals("Rent & bills on ${formatted(today.plusDays(19))}", result.bindingReason)
    }

    @Test
    fun bindingReasonUntilTheMajorIncomeWhenNoOutflowIsNearTheBindingDay() {
        // The rent is long past the 3-day window: what limits the answer is waiting for the loan.
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))),
            scheduledItems = listOf(
                scheduled(1, "Student loan", 3000, 20, major = true),
                scheduled(2, "Rent & bills", -900, 2),
            ),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 60),
        )
        val result = compute(input)
        assertEquals(today.plusDays(19), result.bindingDate)
        assertEquals("Until Student loan on ${formatted(today.plusDays(20))}", result.bindingReason)
    }

    @Test
    fun bindingReasonOverTheNextWeeksWhenTheForecastEndIsTheTightestPoint() {
        // No flows at all: h is constant, so 7h/k keeps falling until the last day.
        fun reason(horizonDays: Int): SafeToSpend = compute(
            TestData.input(
                today, transactions = listOf(opening(Money.pounds(1000))),
                settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = horizonDays),
            ),
        )
        val sixty = reason(60)
        assertEquals(today.plusDays(60), sixty.bindingDate)
        assertEquals("Over the next 9 weeks", sixty.bindingReason) // round(60 / 7) = 9
        assertEquals("Over the next 26 weeks", reason(182).bindingReason)
        assertEquals("Over the next week", reason(7).bindingReason)
    }

    @Test
    fun bindingReasonIsNullWhenNothingExplainsAnInteriorBindingDay() {
        // Rent early, then an income that is NOT a major income: h is tightest the day before it,
        // with no outflow in the window and no major income arriving the next day.
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))),
            scheduledItems = listOf(
                scheduled(1, "Bank transfer in", 3000, 20, major = false),
                scheduled(2, "Rent & bills", -900, 5),
            ),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 60),
        )
        val result = compute(input)
        assertEquals(today.plusDays(19), result.bindingDate)
        assertNull(result.bindingReason)
    }

    @Test
    fun shortfallIsPositiveWhenEvenZeroSpendingBreachesFloorPlusBuffer() {
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(100))),
            scheduledItems = listOf(scheduled(1, "Rent", -300, 5)),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 30),
        )
        val result = compute(input)
        assertEquals(Money.pounds(200), result.shortfall)
        assertEquals(Money.ZERO, result.weekly)
    }

    // --- horizon ---------------------------------------------------------------------------

    @Test
    fun theHorizonIsAlwaysTheForecastEndWhereverTheNextMajorIncomeIs() {
        for (daysAway in listOf(1, 3, 6, 7, 20, 200)) {
            val input = TestData.input(
                today, transactions = listOf(opening(Money.pounds(1000))),
                scheduledItems = listOf(scheduled(1, "Student loan", 3000, daysAway, major = true)),
                settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 90),
            )
            val result = compute(input)
            assertEquals(today.plusDays(90), result.horizonEnd, "income $daysAway days away")
            // The display fields still report the true next major income (if it is in the forecast).
            if (daysAway <= 90) {
                assertEquals(today.plusDays(daysAway.toLong()), result.nextMajorIncome?.date)
                assertEquals(daysAway, result.daysUntilNextMajorIncome)
            } else {
                assertNull(result.nextMajorIncome)
            }
        }
    }

    @Test
    fun nextMajorIncomeIsStillTheFirstOneEvenWhenItIsLessThanSevenDaysAway() {
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))),
            scheduledItems = listOf(
                scheduled(1, "Small refund", 50, 3, major = true),
                scheduled(2, "Student loan", 3000, 40, major = true),
            ),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 90),
        )
        val result = compute(input)
        assertEquals("Small refund", result.nextMajorIncome?.label)
        assertEquals(3, result.daysUntilNextMajorIncome)
        assertEquals(today.plusDays(90), result.horizonEnd)
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
    fun overdueConfirmIncomeCountsAsTomorrowForTheNextMajorIncome() {
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))),
            scheduledItems = listOf(scheduled(1, "Student loan", 1500, 0, major = true)),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 70),
        )
        val result = compute(input)
        assertEquals(today.plusDays(1), result.nextMajorIncome?.date)
        assertEquals(FlowSource.OVERDUE_CONFIRM, result.nextMajorIncome?.source)
        assertEquals(1, result.daysUntilNextMajorIncome)
    }

    // --- regressions found with real data --------------------------------------------------

    @Test
    fun loansDatedTodayGiveAPositiveWeeklyBudgetSpreadOverTheHorizon() {
        // Two student-loan instalments dated today and still unconfirmed (overdue CONFIRM, so
        // they count as tomorrow). The old "< 7 days" rule looked past both and gave horizonEnd ==
        // today and GBP 0.00/week.
        val horizonDays = 70
        val settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = horizonDays)
        val loans = listOf(
            scheduled(1, "Maintenance loan", 1500, 0, major = true),
            scheduled(2, "Tuition top-up", 1500, 0, major = true),
        )
        val withLoans = compute(
            TestData.input(today, transactions = listOf(opening(Money.pounds(1000))), scheduledItems = loans, settings = settings),
        )
        val withoutLoans = compute(
            TestData.input(today, transactions = listOf(opening(Money.pounds(1000))), settings = settings),
        )

        val loanTotal = Money.pounds(3000)
        assertEquals(today.plusDays(horizonDays.toLong()), withLoans.horizonEnd)
        assertEquals(Money.pounds(100), withoutLoans.weekly) // 7 * 1000 / 70 days
        assertTrue(withLoans.weekly.isPositive)
        // The same as without the loans, plus the loans spread evenly over the horizon's weeks.
        assertEquals(withoutLoans.weekly + Money(loanTotal.pence * 7 / horizonDays), withLoans.weekly)
        assertEquals(Money.pounds(400), withLoans.weekly)
        assertEquals(today.plusDays(1), withLoans.nextMajorIncome?.date)
    }

    @Test
    fun aLoanDatedTomorrowAlsoGivesAPositiveWeeklyBudget() {
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))),
            scheduledItems = listOf(scheduled(1, "Student loan", 3000, 1, major = true)),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 70),
        )
        val result = compute(input)
        assertEquals(today.plusDays(70), result.horizonEnd)
        assertEquals(Money.pounds(400), result.weekly)
    }

    @Test
    fun incomesThreeAndFiveDaysAwayAreNotExtrapolatedFromAShortHorizon() {
        // The old rule ended the horizon on day 4 and scaled that 4 days' headroom up to a week
        // (thousands of pounds a week). Now the budget has to last the whole forecast.
        val horizonDays = 70
        val buffer = Money.pounds(100)
        val pool = Money.pounds(200)
        val input = TestData.input(
            today, transactions = listOf(opening(pool)),
            scheduledItems = listOf(
                scheduled(1, "Student loan A", 800, 3, major = true),
                scheduled(2, "Student loan B", 800, 5, major = true),
                scheduled(3, "Rent & bills", -300, 10),
                scheduled(4, "Term fees", -150, 40),
            ),
            settings = TestData.settings(buffer = buffer, forecastHorizonDays = horizonDays),
        )
        val result = compute(input)

        val income = Money.pounds(1600)
        val costs = Money.pounds(450)
        val upperBound = Money((pool + income - costs - buffer).pence * 7 / horizonDays)
        assertTrue(result.weekly <= upperBound, "weekly ${result.weekly} exceeds the whole-horizon average $upperBound")
        assertEquals(Money.pounds(125), upperBound)
        assertEquals(
            referenceWeekly(
                pool.pence, buffer.pence, horizonDays,
                listOf(3 to 80000L, 5 to 80000L, 10 to -30000L, 40 to -15000L),
            ),
            result.weekly,
        )
        assertEquals(today.plusDays(horizonDays.toLong()), result.bindingDate)
        assertEquals("Over the next 10 weeks", result.bindingReason)
        assertEquals(3, result.daysUntilNextMajorIncome)
    }

    @Test
    fun weeklyWagesMarkedAsMajorIncomeGiveASensibleBudget() {
        val horizonDays = 182
        val buffer = Money.pounds(50)
        val pool = Money.pounds(250)
        val wages = RecurringRule(
            id = 1, label = "Wages", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(180), frequency = Frequency.WEEKLY, startDate = today.plusDays(2),
            postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val rent = RecurringRule(
            id = 2, label = "Rent & bills", accountId = TestData.CURRENT, categoryId = TestData.RENT,
            amount = Money.pounds(-450), frequency = Frequency.MONTHLY, startDate = today.plusDays(10),
            postingMode = PostingMode.AUTO,
        )
        fun input(major: Boolean) = TestData.input(
            today, transactions = listOf(opening(pool)),
            recurringRules = listOf(wages.copy(isMajorIncome = major), rent),
            settings = TestData.settings(buffer = buffer, forecastHorizonDays = horizonDays),
        )
        val result = compute(input(major = true))

        // Reference: wages every 7 days from day 2, rent monthly from day 10, all within 182 days.
        val flows = mutableListOf<Pair<Int, Long>>()
        for (wageDay in 2..horizonDays step 7) flows += wageDay to 18000L
        for (m in 0L..12L) {
            val day = ChronoUnit.DAYS.between(today, today.plusDays(10).plusMonths(m)).toInt()
            if (day <= horizonDays) flows += day to -45000L
        }
        assertEquals(referenceWeekly(pool.pence, buffer.pence, horizonDays, flows), result.weekly)
        assertTrue(result.weekly.isPositive)
        assertTrue(result.weekly < Money.pounds(200), "weekly wages must not blow the budget up: ${result.weekly}")

        // Flagging the wages as major income changes the display fields only, never the number.
        val notMajor = compute(input(major = false))
        assertEquals(notMajor.weekly, result.weekly)
        assertEquals(notMajor.bindingDate, result.bindingDate)
        assertNull(notMajor.nextMajorIncome)
        assertEquals(2, result.daysUntilNextMajorIncome)
    }

    // --- unchanged behaviour ---------------------------------------------------------------

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

    @Test
    fun theEngineReportsTheSameSafeToSpendAsTheCalculator() {
        val input = TestData.input(
            today, transactions = listOf(opening(Money.pounds(1000))),
            scheduledItems = listOf(scheduled(1, "Student loan", 3000, 0, major = true)),
            settings = TestData.settings(buffer = Money.ZERO, forecastHorizonDays = 70),
        )
        assertEquals(compute(input), ForecastEngine.run(input).safeToSpend)
    }

    private fun formatted(date: LocalDate): String {
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "${date.dayOfMonth} ${months[date.monthValue - 1]}"
    }
}
