package io.github.matteopi.budgeting.ui.home

import io.github.matteopi.budgeting.core.forecast.CommittedFlow
import io.github.matteopi.budgeting.core.forecast.FlowSource
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.domain.ForecastState
import io.github.matteopi.budgeting.domain.snapshot
import io.github.matteopi.budgeting.testing.ForecastFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeMappingTest {
    private val input = ForecastFixtures.input()
    private val result = ForecastFixtures.result(input)

    @Test
    fun `the total is the pool balance, read from the same input as the account rows`() {
        val balance = HomeMapping.balance(AccountView.Total, input, result)
        assertEquals(AccountView.Total, balance.view)
        // Current £1,000 + Savings £500; Cash is outside the pool.
        assertEquals(Money.pounds(1_500), balance.display)
        assertEquals(Money.ZERO, balance.floorDisplay)
    }

    @Test
    fun `the overdraft zero point adds the pool's overdraft to the total`() {
        val overdraftInput = ForecastFixtures.input(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT)
        val overdraftResult = ForecastFixtures.result(overdraftInput)
        val balance = HomeMapping.balance(AccountView.Total, overdraftInput, overdraftResult)
        assertEquals(Money.pounds(1_500 + 250), balance.display)
        assertEquals(Money.ZERO, balance.floorDisplay)
    }

    @Test
    fun `one account shows that account's balance, converted with its own overdraft`() {
        val savings = HomeMapping.balance(AccountView.Single(ForecastFixtures.SAVINGS), input, result)
        assertEquals(AccountView.Single(ForecastFixtures.SAVINGS), savings.view)
        assertEquals(Money.pounds(500), savings.display)

        val overdraftInput = ForecastFixtures.input(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT)
        val overdraftResult = ForecastFixtures.result(overdraftInput)
        assertEquals(
            Money.pounds(550),
            HomeMapping.balance(AccountView.Single(ForecastFixtures.SAVINGS), overdraftInput, overdraftResult).display,
        )
        assertEquals(
            Money.pounds(1_200),
            HomeMapping.balance(AccountView.Single(ForecastFixtures.CURRENT), overdraftInput, overdraftResult).display,
        )
    }

    @Test
    fun `an account outside the pool can be the balance too`() {
        assertEquals(Money.pounds(20), HomeMapping.balance(AccountView.Single(ForecastFixtures.CASH), input, result).display)
    }

    @Test
    fun `a missing, archived or each view shows the pool total`() {
        val archivedInput = ForecastFixtures.input(
            accounts = ForecastFixtures.accounts().map { if (it.id == ForecastFixtures.CASH) it.copy(archived = true) else it },
        )
        val archivedResult = ForecastFixtures.result(archivedInput)
        val views = listOf(
            AccountView.Single(999) to (input to result),
            AccountView.Single(ForecastFixtures.CASH) to (archivedInput to archivedResult),
            AccountView.Each to (input to result),
        )
        for ((view, pair) in views) {
            val balance = HomeMapping.balance(view, pair.first, pair.second)
            assertEquals(AccountView.Total, balance.view, "$view")
            assertEquals(Money.pounds(1_500), balance.display, "$view")
        }
    }

    @Test
    fun `the sparkline is eight weekly points of the pool, or of the chosen account`() {
        val total = HomeMapping.balance(AccountView.Total, input, result)
        assertEquals(8, total.sparkline.size)
        assertEquals(listOf(6, 13, 20, 27, 34, 41, 48, 55).map { result.days[it].expectedBalance }, total.sparkline)

        val savingsForecast = result.accountForecasts.first { it.accountId == ForecastFixtures.SAVINGS }
        val savings = HomeMapping.balance(AccountView.Single(ForecastFixtures.SAVINGS), input, result)
        assertEquals(8, savings.sparkline.size)
        assertEquals(listOf(6, 13, 20, 27, 34, 41, 48, 55).map { savingsForecast.days[it].expectedBalance }, savings.sparkline)
    }

    @Test
    fun `the sparkline is empty until there is a forecast`() {
        assertEquals(emptyList(), HomeMapping.balance(AccountView.Total, input, null).sparkline)
        assertEquals(
            emptyList(),
            HomeMapping.balance(AccountView.Single(ForecastFixtures.SAVINGS), input, null).sparkline,
        )
    }

    @Test
    fun `a forecast worked out before any account existed reads zero and a buffer-sized shortfall`() {
        // This is what Home once showed next to three real accounts: a result computed from an
        // older, empty input. It is right for THAT input, which is why the balance, the account
        // rows and the result must always be drawn from one snapshot.
        val empty = ForecastFixtures.input(accounts = emptyList(), transactions = emptyList())
        val emptyResult = ForecastFixtures.result(empty)
        assertEquals(Money.ZERO, emptyResult.poolBalanceNow)
        assertEquals(Money.pounds(100), emptyResult.safeToSpend.shortfall)

        // A snapshot pairs the input with ITS result, so the balance and the rows agree.
        val snapshot = ForecastState.Ready(empty, emptyResult, 0L).snapshot()!!
        assertEquals(Money.ZERO, HomeMapping.balance(AccountView.Total, snapshot.input, snapshot.result).display)
        assertEquals(0, snapshot.input.accounts.size)
    }

    @Test
    fun `a forecast worked out for the day before misses transactions dated today`() {
        val lagging = ForecastFixtures.input(today = ForecastFixtures.today.minusDays(1))
        assertEquals(Money.ZERO, ForecastFixtures.result(lagging).poolBalanceNow)
        assertEquals(Money.pounds(1_500), result.poolBalanceNow)
    }

    private fun safeToSpend(
        bindingReason: String?,
        daysUntil: Int?,
        incomeLabel: String? = "Student loan",
    ): SafeToSpend {
        val income = if (daysUntil != null && incomeLabel != null) {
            CommittedFlow(
                date = ForecastFixtures.today.plusDays(daysUntil.toLong()),
                amount = Money.pounds(1_000),
                label = incomeLabel,
                source = FlowSource.SCHEDULED,
                sourceId = 1,
                categoryId = null,
                accountId = null,
                isMajorIncome = true,
            )
        } else {
            null
        }
        return result.safeToSpend.copy(
            weekly = Money.pounds(71),
            perDay = Money.pounds(10),
            nextMajorIncome = income,
            daysUntilNextMajorIncome = daysUntil,
            bindingReason = bindingReason,
        )
    }

    @Test
    fun `the hero says what limits the figure and how long until the next money in`() {
        val detail = HomeMapping.heroDetail(safeToSpend(bindingReason = "Rent on 1 Oct", daysUntil = 12))
        assertEquals("£10 a day · Rent on 1 Oct", detail.perDayLine)
        assertEquals("12 days to Student loan", detail.nextIncomeLine)
    }

    @Test
    fun `the hero keeps the per-day figure when the engine names no reason and no income is coming`() {
        val detail = HomeMapping.heroDetail(safeToSpend(bindingReason = null, daysUntil = null))
        assertEquals("£10 a day", detail.perDayLine)
        assertEquals(null, detail.nextIncomeLine)
    }

    @Test
    fun `the hero uses the singular for one day and a fallback name for an unlabelled income`() {
        assertEquals("1 day to Student loan", HomeMapping.heroDetail(safeToSpend("Over the next 26 weeks", 1)).nextIncomeLine)
        assertEquals(
            "3 days to next money in",
            HomeMapping.heroDetail(safeToSpend("Over the next 26 weeks", 3, incomeLabel = "  ")).nextIncomeLine,
        )
    }
}
