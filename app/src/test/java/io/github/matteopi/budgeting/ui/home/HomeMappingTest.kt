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
    fun `the pool is the safe-to-spend accounts' balance, read from the same input as the account rows`() {
        val balance = HomeMapping.balance(AccountView.Pool, input, result)
        assertEquals(AccountView.Pool, balance.view)
        // Current £1,000 + Savings £500; Cash is outside the pool.
        assertEquals(Money.pounds(1_500), balance.display)
        assertEquals(Money.ZERO, balance.floorDisplay)
    }

    @Test
    fun `all accounts sums every account, including the ones outside the pool`() {
        val balance = HomeMapping.balance(AccountView.All, input, result)
        assertEquals(AccountView.All, balance.view)
        // Current £1,000 + Savings £500 + Cash £20.
        assertEquals(Money.pounds(1_520), balance.display)
        assertEquals(Money.ZERO, balance.floorDisplay)
    }

    @Test
    fun `all accounts leaves out archived accounts`() {
        val archivedInput = ForecastFixtures.input(
            accounts = ForecastFixtures.accounts().map { if (it.id == ForecastFixtures.CASH) it.copy(archived = true) else it },
        )
        val archivedResult = ForecastFixtures.result(archivedInput)
        assertEquals(Money.pounds(1_500), HomeMapping.balance(AccountView.All, archivedInput, archivedResult).display)
    }

    @Test
    fun `the pool view is shown as all accounts when no account is left out of safe-to-spend`() {
        val everyAccount = ForecastFixtures.input(accounts = ForecastFixtures.accounts().map { it.copy(includeInSafeToSpend = true) })
        val everyResult = ForecastFixtures.result(everyAccount)
        val balance = HomeMapping.balance(AccountView.Pool, everyAccount, everyResult)
        assertEquals(AccountView.All, balance.view)
        assertEquals(Money.pounds(1_520), balance.display)
        assertEquals(HomeMapping.balance(AccountView.All, everyAccount, everyResult), balance)
    }

    @Test
    fun `the overdraft zero point adds the matching overdrafts to each total`() {
        val overdraftInput = ForecastFixtures.input(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT)
        val overdraftResult = ForecastFixtures.result(overdraftInput)
        val pool = HomeMapping.balance(AccountView.Pool, overdraftInput, overdraftResult)
        assertEquals(Money.pounds(1_500 + 250), pool.display)
        assertEquals(Money.ZERO, pool.floorDisplay)

        val all = HomeMapping.balance(AccountView.All, overdraftInput, overdraftResult)
        // Cash has no overdraft, so all accounts adds just its £20.
        assertEquals(Money.pounds(1_520 + 250), all.display)
        assertEquals(Money.ZERO, all.floorDisplay)
    }

    @Test
    fun `all accounts adds the overdraft of an account outside the pool`() {
        val accounts = ForecastFixtures.accounts().map {
            if (it.id == ForecastFixtures.CASH) it.copy(overdraftLimit = Money.pounds(30)) else it
        }
        val overdraftInput = ForecastFixtures.input(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT, accounts = accounts)
        val overdraftResult = ForecastFixtures.result(overdraftInput)
        val all = HomeMapping.balance(AccountView.All, overdraftInput, overdraftResult)
        assertEquals(Money.pounds(1_520 + 250 + 30), all.display)
        assertEquals(Money.ZERO, all.floorDisplay)
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
    fun `a missing, archived or each view shows all accounts`() {
        val archivedInput = ForecastFixtures.input(
            accounts = ForecastFixtures.accounts().map { if (it.id == ForecastFixtures.CASH) it.copy(archived = true) else it },
        )
        val archivedResult = ForecastFixtures.result(archivedInput)
        val views = listOf(
            Triple(AccountView.Single(999), input to result, Money.pounds(1_520)),
            Triple(AccountView.Single(ForecastFixtures.CASH), archivedInput to archivedResult, Money.pounds(1_500)),
            Triple(AccountView.Each, input to result, Money.pounds(1_520)),
        )
        for ((view, pair, expected) in views) {
            val balance = HomeMapping.balance(view, pair.first, pair.second)
            assertEquals(AccountView.All, balance.view, "$view")
            assertEquals(expected, balance.display, "$view")
        }
    }

    @Test
    fun `the sparkline is eight weekly points of the pool, or of the chosen account`() {
        val weekly = listOf(6, 13, 20, 27, 34, 41, 48, 55)
        val pool = HomeMapping.balance(AccountView.Pool, input, result)
        assertEquals(8, pool.sparkline.size)
        assertEquals(weekly.map { result.days[it].expectedBalance }, pool.sparkline)

        // All accounts adds the Cash account (£20, nothing scheduled) to the pool's expected balance.
        val cashForecast = result.accountForecasts.first { it.accountId == ForecastFixtures.CASH }
        val all = HomeMapping.balance(AccountView.All, input, result)
        assertEquals(8, all.sparkline.size)
        assertEquals(weekly.map { result.days[it].expectedBalance + cashForecast.days[it].expectedBalance }, all.sparkline)
        assertEquals(weekly.map { result.days[it].expectedBalance + Money.pounds(20) }, all.sparkline)

        val savingsForecast = result.accountForecasts.first { it.accountId == ForecastFixtures.SAVINGS }
        val savings = HomeMapping.balance(AccountView.Single(ForecastFixtures.SAVINGS), input, result)
        assertEquals(8, savings.sparkline.size)
        assertEquals(listOf(6, 13, 20, 27, 34, 41, 48, 55).map { savingsForecast.days[it].expectedBalance }, savings.sparkline)
    }

    @Test
    fun `the sparkline is empty until there is a forecast`() {
        assertEquals(emptyList(), HomeMapping.balance(AccountView.All, input, null).sparkline)
        assertEquals(emptyList(), HomeMapping.balance(AccountView.Pool, input, null).sparkline)
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
        assertEquals(Money.ZERO, HomeMapping.balance(AccountView.All, snapshot.input, snapshot.result).display)
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
