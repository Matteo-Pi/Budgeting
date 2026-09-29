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
    fun `the total view is unchanged and has no per-account rows`() {
        val default = TimelineMapping.buildWeeks(result, emptyList(), ZeroPoint.ACCOUNT_BALANCE)
        val total = weeks(AccountView.Total)
        assertEquals(default, total)
        assertTrue(total.all { it.accountBalances.isEmpty() && it.showPoolFigures })
    }

    @Test
    fun `one account shows that account's end-of-week balance and hides the pool-only rows`() {
        val forecast = result.accountForecasts.first { it.accountId == ForecastFixtures.SAVINGS }
        val byDate = forecast.days.associateBy { it.date }
        val single = weeks(AccountView.Single(ForecastFixtures.SAVINGS))
        val total = weeks(AccountView.Total)
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
    fun `an account view with no per-account forecasts shows the pool instead`() {
        val bare = result.copy(accountForecasts = emptyList())
        val total = TimelineMapping.buildWeeks(bare, emptyList(), ZeroPoint.ACCOUNT_BALANCE)
        for (view in listOf(AccountView.Each, AccountView.Single(ForecastFixtures.CURRENT))) {
            assertEquals(total, TimelineMapping.buildWeeks(bare, emptyList(), ZeroPoint.ACCOUNT_BALANCE, input.accounts, view))
        }
    }
}
