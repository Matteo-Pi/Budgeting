package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.testing.ForecastFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelineAccountViewTest {
    private val input = ForecastFixtures.input()
    private val result = ForecastFixtures.result(input)

    private fun weeks(view: AccountView, zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE) =
        TimelineMapping.buildWeeks(result, emptyList(), zeroPoint, input.accounts, view)

    @Test
    fun `all accounts is the default view and has no per-account rows`() {
        val default = TimelineMapping.buildWeeks(result, emptyList(), ZeroPoint.ACCOUNT_BALANCE)
        val all = weeks(AccountView.All)
        assertEquals(default, all)
        assertTrue(all.all { it.accountBalances.isEmpty() && it.showPoolFigures })
    }

    @Test
    fun `the pool view is the safe-to-spend balance with every pool figure`() {
        val pool = weeks(AccountView.Pool)
        assertTrue(pool.all { it.accountBalances.isEmpty() && it.showPoolFigures && it.showReserved })
        pool.forEach { week ->
            val lastDay = result.days.drop(1).filter { it.date >= week.weekStart && it.date <= week.weekStart.plusDays(6) }.maxBy { it.date }
            assertEquals(lastDay.p50, week.endBalanceP50)
            assertEquals(lastDay.p10, week.endBalanceP10)
            assertEquals(lastDay.p90, week.endBalanceP90)
        }
    }

    @Test
    fun `all accounts adds the accounts outside the pool, lists every flow and hides the pool-only reserved row`() {
        val pool = weeks(AccountView.Pool)
        val all = weeks(AccountView.All)
        val cash = Money.pounds(20) // Cash holds £20 outside the pool with nothing scheduled against it.
        assertEquals(pool.size, all.size)
        all.forEachIndexed { i, week ->
            assertEquals(pool[i].weekStart, week.weekStart)
            assertEquals(pool[i].endBalanceP50 + cash, week.endBalanceP50, "p50 in week $i")
            assertEquals(pool[i].endBalanceP10!! + cash, week.endBalanceP10, "p10 in week $i")
            assertEquals(pool[i].endBalanceP90!! + cash, week.endBalanceP90, "p90 in week $i")
            assertEquals(pool[i].flows, week.flows)
            assertEquals(pool[i].expectedSpending, week.expectedSpending)
            assertTrue(week.accountBalances.isEmpty())
            assertTrue(week.showPoolFigures)
            assertEquals(false, week.showReserved)
        }
    }

    @Test
    fun `all accounts is converted with the combined overdraft`() {
        val accounts = ForecastFixtures.accounts().map {
            if (it.id == ForecastFixtures.CASH) it.copy(overdraftLimit = Money.pounds(30)) else it
        }
        val withOverdraft = ForecastFixtures.input(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT, accounts = accounts)
        val r = ForecastFixtures.result(withOverdraft)
        val all = TimelineMapping.buildWeeks(r, emptyList(), ZeroPoint.OVERDRAFT_LIMIT, withOverdraft.accounts, AccountView.All)
        val pool = TimelineMapping.buildWeeks(r, emptyList(), ZeroPoint.OVERDRAFT_LIMIT, withOverdraft.accounts, AccountView.Pool)
        // £20 Cash plus its own £30 overdraft: £50 more than the pool's figure.
        assertEquals(pool.first().endBalanceP50 + Money.pounds(50), all.first().endBalanceP50)
    }

    @Test
    fun `all accounts is exactly the pool view when every account is in the pool`() {
        val everyAccount = ForecastFixtures.input(accounts = ForecastFixtures.accounts().map { it.copy(includeInSafeToSpend = true) })
        val r = ForecastFixtures.result(everyAccount)
        val all = TimelineMapping.buildWeeks(r, emptyList(), ZeroPoint.ACCOUNT_BALANCE, everyAccount.accounts, AccountView.All)
        val pool = TimelineMapping.buildWeeks(r, emptyList(), ZeroPoint.ACCOUNT_BALANCE, everyAccount.accounts, AccountView.Pool)
        assertEquals(pool, all)
        assertTrue(all.all { it.showReserved })
    }

    @Test
    fun `one account shows that account's end-of-week balance and hides the pool-only rows`() {
        val forecast = result.accountForecasts.first { it.accountId == ForecastFixtures.SAVINGS }
        val byDate = forecast.days.associateBy { it.date }
        val single = weeks(AccountView.Single(ForecastFixtures.SAVINGS))
        val total = weeks(AccountView.Pool)
        assertEquals(total.size, single.size)
        single.forEachIndexed { i, week ->
            assertEquals(false, week.showPoolFigures)
            assertTrue(week.accountBalances.isEmpty())
            // The last forecast day of each week is what the end-of-week balance is read from.
            val lastDate = result.days.drop(1).filter { it.date >= week.weekStart && it.date <= week.weekStart.plusDays(6) }.maxOf { it.date }
            assertEquals(byDate.getValue(lastDate).p50, week.endBalanceP50)
            assertEquals(byDate.getValue(lastDate).p10, week.endBalanceP10)
            assertEquals(total[i].weekStart, week.weekStart)
        }
    }

    @Test
    fun `one account is converted with its own overdraft`() {
        val week = weeks(AccountView.Single(ForecastFixtures.SAVINGS), ZeroPoint.OVERDRAFT_LIMIT).first()
        val raw = result.accountForecasts.first { it.accountId == ForecastFixtures.SAVINGS }
            .days.first { it.date == result.days.drop(1).filter { d -> d.date <= week.weekStart.plusDays(6) }.maxOf { d -> d.date } }
        assertEquals((raw.p50 ?: raw.expectedBalance) + Money.pounds(50), week.endBalanceP50)
    }

    @Test
    fun `each account gives one balance per account, in creation order`() {
        val each = weeks(AccountView.Each)
        val week = each.first()
        assertEquals(listOf("Current", "Savings", "Cash"), week.accountBalances.map { it.name })
        assertTrue(week.showPoolFigures)
        val savings = week.accountBalances.first { it.accountId == ForecastFixtures.SAVINGS }
        val p10 = assertNotNull(savings.p10)
        val p90 = assertNotNull(savings.p90)
        assertTrue(p10 <= savings.p50 && savings.p50 <= p90)
    }

    @Test
    fun `an account view with no per-account forecasts shows all accounts instead`() {
        val bare = result.copy(accountForecasts = emptyList())
        val total = TimelineMapping.buildWeeks(bare, emptyList(), ZeroPoint.ACCOUNT_BALANCE)
        for (view in listOf(AccountView.Each, AccountView.Single(ForecastFixtures.CURRENT))) {
            assertEquals(total, TimelineMapping.buildWeeks(bare, emptyList(), ZeroPoint.ACCOUNT_BALANCE, input.accounts, view))
        }
    }
}
