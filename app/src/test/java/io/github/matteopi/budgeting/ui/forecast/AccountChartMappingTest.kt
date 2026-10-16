package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.testing.ForecastFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountChartMappingTest {
    private val buffer = Money.pounds(100)

    private fun build(view: AccountView, zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE): ForecastChartData {
        val input = ForecastFixtures.input(zeroPoint = zeroPoint)
        val result = ForecastFixtures.result(input)
        return AccountChartMapping.build(view, result, input.accounts, zeroPoint, buffer)
    }

    @Test
    fun `the pool is the existing safe-to-spend chart with its buffer and tier-1 line`() {
        val input = ForecastFixtures.input()
        val result = ForecastFixtures.result(input)
        val data = AccountChartMapping.build(AccountView.Pool, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        assertEquals(AccountView.Pool, data.view)
        assertNull(data.lines)
        assertEquals(buffer, data.buffer)
        assertTrue(data.hasTier1)
        assertEquals(ForecastChartMapping.toChartDays(result, ZeroPoint.ACCOUNT_BALANCE), data.days)
        assertTrue(data.days.all { it.tier1 != null })
    }

    @Test
    fun `all accounts adds the accounts outside the pool to every percentile, with no buffer and no tier-1`() {
        val input = ForecastFixtures.input()
        val result = ForecastFixtures.result(input)
        val pool = AccountChartMapping.build(AccountView.Pool, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        val all = AccountChartMapping.build(AccountView.All, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        val cash = result.accountForecasts.first { it.accountId == ForecastFixtures.CASH }
        assertEquals(AccountView.All, all.view)
        assertNull(all.lines)
        assertEquals(Money.ZERO, all.buffer)
        assertEquals(false, all.hasTier1)
        assertTrue(all.days.all { it.tier1 == null })
        assertEquals(pool.days.map { it.date }, all.days.map { it.date })
        assertEquals(pool.days.map { it.regime }, all.days.map { it.regime })
        // Today: Current £1,000 + Savings £500 in the pool, plus Cash £20 outside it.
        assertEquals(Money.pounds(1_520), all.days.first().p50)
        all.days.forEachIndexed { i, day ->
            val extra = cash.days[i].expectedBalance
            assertEquals(pool.days[i].p10!! + extra, day.p10, "p10 on day $i")
            assertEquals(pool.days[i].p50 + extra, day.p50, "p50 on day $i")
            assertEquals(pool.days[i].p90!! + extra, day.p90, "p90 on day $i")
        }
    }

    @Test
    fun `all accounts are converted with the pool's overdraft plus the other accounts' own`() {
        val accounts = ForecastFixtures.accounts().map {
            if (it.id == ForecastFixtures.CASH) it.copy(overdraftLimit = Money.pounds(30)) else it
        }
        val input = ForecastFixtures.input(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT, accounts = accounts)
        val result = ForecastFixtures.result(input)
        val all = AccountChartMapping.build(AccountView.All, result, input.accounts, ZeroPoint.OVERDRAFT_LIMIT, buffer)
        // £1,520 raw + (£200 + £50 pool overdraft + £30 Cash overdraft).
        assertEquals(Money.pounds(1_800), all.days.first().p50)
        val pool = AccountChartMapping.build(AccountView.Pool, result, input.accounts, ZeroPoint.OVERDRAFT_LIMIT, buffer)
        assertEquals(Money.pounds(1_750), pool.days.first().p50)
    }

    @Test
    fun `all accounts is the pool chart itself when every account is in the pool`() {
        val accounts = ForecastFixtures.accounts().map { it.copy(includeInSafeToSpend = true) }
        val input = ForecastFixtures.input(accounts = accounts)
        val result = ForecastFixtures.result(input)
        val pool = AccountChartMapping.build(AccountView.Pool, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        val all = AccountChartMapping.build(AccountView.All, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        assertEquals(AccountView.All, all.view)
        assertEquals(pool.copy(view = AccountView.All), all)
        assertEquals(buffer, all.buffer)
        assertTrue(all.hasTier1)
        assertEquals(Money.pounds(1_520), all.days.first().p50)
    }

    @Test
    fun `one account charts that account's own band and expected line, with no buffer and no tier-1`() {
        val input = ForecastFixtures.input()
        val result = ForecastFixtures.result(input)
        val data = AccountChartMapping.build(
            AccountView.Single(ForecastFixtures.SAVINGS), result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer,
        )
        val forecast = result.accountForecasts.first { it.accountId == ForecastFixtures.SAVINGS }
        assertEquals(AccountView.Single(ForecastFixtures.SAVINGS), data.view)
        assertNull(data.lines)
        assertEquals(Money.ZERO, data.buffer)
        assertEquals(false, data.hasTier1)
        assertEquals(result.days.size, data.days.size)
        assertEquals(result.days.map { it.date }, data.days.map { it.date })
        assertEquals(result.days.map { it.regime }, data.days.map { it.regime })
        data.days.forEachIndexed { i, day ->
            assertEquals(forecast.days[i].p10, day.p10)
            assertEquals(forecast.days[i].p90, day.p90)
            assertEquals(forecast.days[i].p50, day.p50)
            assertNull(day.tier1)
        }
        assertNotNull(data.days.first().p10, "Monte Carlo is on, so the account has a band")
    }

    @Test
    fun `one account is converted with its own overdraft, not the pool's`() {
        val input = ForecastFixtures.input(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT)
        val result = ForecastFixtures.result(input)
        val savings = AccountChartMapping.build(
            AccountView.Single(ForecastFixtures.SAVINGS), result, input.accounts, ZeroPoint.OVERDRAFT_LIMIT, buffer,
        )
        val current = AccountChartMapping.build(
            AccountView.Single(ForecastFixtures.CURRENT), result, input.accounts, ZeroPoint.OVERDRAFT_LIMIT, buffer,
        )
        // Today: Savings holds £500 with a £50 overdraft; Current holds £1,000 with a £200 overdraft.
        assertEquals(Money.pounds(550), savings.days.first().p50)
        assertEquals(Money.pounds(1_200), current.days.first().p50)
    }

    @Test
    fun `with the account balance zero point one account is shown raw`() {
        val data = build(AccountView.Single(ForecastFixtures.SAVINGS))
        assertEquals(Money.pounds(500), data.days.first().p50)
    }

    @Test
    fun `an account outside the pool can be charted too`() {
        val data = build(AccountView.Single(ForecastFixtures.CASH))
        assertEquals(AccountView.Single(ForecastFixtures.CASH), data.view)
        assertEquals(Money.pounds(20), data.days.first().p50)
    }

    @Test
    fun `an account with no forecast falls back to all accounts`() {
        val data = build(AccountView.Single(999))
        assertEquals(AccountView.All, data.view)
        assertNull(data.lines)
        assertEquals(Money.ZERO, data.buffer)
        assertEquals(Money.pounds(1_520), data.days.first().p50)
    }

    @Test
    fun `each account gives one expected-balance line per account, with no buffer, band or tier-1`() {
        val input = ForecastFixtures.input()
        val result = ForecastFixtures.result(input)
        val data = AccountChartMapping.build(AccountView.Each, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        val lines = assertNotNull(data.lines)
        assertEquals(AccountView.Each, data.view)
        assertEquals(listOf("Current", "Savings", "Cash"), lines.map { it.label })
        assertEquals(Money.ZERO, data.buffer)
        assertEquals(false, data.hasTier1)
        lines.forEach { line -> assertEquals(result.days.size, line.points.size) }
        assertEquals(Money.pounds(1_000), lines[0].points.first().second)
        assertEquals(Money.pounds(500), lines[1].points.first().second)
        assertEquals(Money.pounds(20), lines[2].points.first().second)
        // The date axis and term shading still come from the pool's days.
        assertEquals(result.days.map { it.date }, data.days.map { it.date })
    }

    @Test
    fun `each account lines use each account's own overdraft for the display zero point`() {
        val input = ForecastFixtures.input(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT)
        val result = ForecastFixtures.result(input)
        val data = AccountChartMapping.build(AccountView.Each, result, input.accounts, ZeroPoint.OVERDRAFT_LIMIT, buffer)
        val lines = assertNotNull(data.lines)
        assertEquals(Money.pounds(1_200), lines[0].points.first().second)
        assertEquals(Money.pounds(550), lines[1].points.first().second)
        assertEquals(Money.pounds(20), lines[2].points.first().second)
    }

    @Test
    fun `an archived account has no line`() {
        val accounts = ForecastFixtures.accounts().map { if (it.id == ForecastFixtures.CASH) it.copy(archived = true) else it }
        val input = ForecastFixtures.input(accounts = accounts)
        val result = ForecastFixtures.result(input)
        val data = AccountChartMapping.build(AccountView.Each, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        assertEquals(listOf("Current", "Savings"), assertNotNull(data.lines).map { it.label })
    }

    @Test
    fun `the pool accounts' expected lines add up to the pool line`() {
        val input = ForecastFixtures.input()
        val result = ForecastFixtures.result(input)
        val data = AccountChartMapping.build(AccountView.Each, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        val lines = assertNotNull(data.lines)
        result.days.forEachIndexed { i, day ->
            val poolSum = lines.take(2).sumOf { it.points[i].second.pence }
            assertEquals(day.expectedBalance.pence, poolSum, "day $i")
        }
    }

    @Test
    fun `without per-account forecasts every view falls back to all accounts`() {
        val input = ForecastFixtures.input()
        val result = ForecastFixtures.result(input).copy(accountForecasts = emptyList())
        for (view in listOf(AccountView.Each, AccountView.Single(ForecastFixtures.CURRENT))) {
            val data = AccountChartMapping.build(view, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
            assertEquals(AccountView.All, data.view)
            assertNull(data.lines)
        }
    }

    @Test
    fun `cutting at a horizon trims the days and every line`() {
        val input = ForecastFixtures.input()
        val result = ForecastFixtures.result(input)
        val data = AccountChartMapping.build(AccountView.Each, result, input.accounts, ZeroPoint.ACCOUNT_BALANCE, buffer)
        val end = ForecastFixtures.today.plusDays(10)
        val cut = AccountChartMapping.cutAt(data, end)
        assertEquals(11, cut.days.size)
        assertEquals(end, cut.days.last().date)
        assertTrue(assertNotNull(cut.lines).all { line -> line.points.size == 11 && line.points.last().first == end })
    }
}
