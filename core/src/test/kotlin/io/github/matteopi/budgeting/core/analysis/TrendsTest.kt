package io.github.matteopi.budgeting.core.analysis

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SystemKeys
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrendsTest {

    private val pool = Account(id = 1, name = "Current", type = AccountType.CURRENT, includeInSafeToSpend = true)
    private val outsidePool = Account(id = 2, name = "Savings", type = AccountType.SAVINGS, includeInSafeToSpend = false)
    private val groceries = Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
    private val untracked = Category(
        id = 2, name = "Untracked", flow = Flow.BOTH, forecastMode = ForecastMode.LEARNED,
        isSystem = true, systemKey = SystemKeys.UNTRACKED,
    )
    private val accounts = listOf(pool, outsidePool)
    private val categories = listOf(groceries, untracked)

    private fun tx(
        accountId: Long, categoryId: Long?, date: LocalDate, pence: Long, kind: TxKind = TxKind.NORMAL,
    ) = Transaction(accountId = accountId, amount = Money(pence), date = date, categoryId = categoryId, kind = kind)

    @Test
    fun `weeklyByCategory sums signed amounts per Monday-start week`() {
        val monday = LocalDate.of(2026, 9, 21)
        val transactions = listOf(
            tx(pool.id, groceries.id, monday, -1200),
            tx(pool.id, groceries.id, monday.plusDays(2), -300),
            tx(pool.id, groceries.id, monday.plusDays(9), -500), // next week
        )
        val weeks = Trends.weeklyByCategory(accounts, categories, transactions, monday, monday.plusDays(13))

        assertEquals(2, weeks.size)
        assertEquals(Money(-1500), weeks[0].totals[groceries.id])
        assertEquals(Money(-500), weeks[1].totals[groceries.id])
        assertEquals(Money.ZERO, weeks[0].totals[untracked.id]) // every known category is present, zero-filled
    }

    @Test
    fun `weeklyByCategory excludes transfers, opening balances, and non-pool accounts`() {
        val monday = LocalDate.of(2026, 9, 21)
        val transactions = listOf(
            tx(pool.id, null, monday, -1000, kind = TxKind.TRANSFER),
            tx(pool.id, null, monday, 500000, kind = TxKind.OPENING_BALANCE),
            tx(outsidePool.id, groceries.id, monday, -999),
            tx(pool.id, groceries.id, monday, -100),
        )
        val weeks = Trends.weeklyByCategory(accounts, categories, transactions, monday, monday.plusDays(6))
        assertEquals(Money(-100), weeks.single().totals[groceries.id])
    }

    @Test
    fun `weeklyByCategory counts a reconciliation under whatever category it is tagged with`() {
        val monday = LocalDate.of(2026, 9, 21)
        val transactions = listOf(tx(pool.id, untracked.id, monday, -250, kind = TxKind.RECONCILIATION))
        val weeks = Trends.weeklyByCategory(accounts, categories, transactions, monday, monday.plusDays(6))
        assertEquals(Money(-250), weeks.single().totals[untracked.id])
    }

    @Test
    fun `termVsHoliday averages weekly spend per regime over complete weeks in the data`() {
        // Two consecutive Monday-start weeks fully inside the term, then one immediately after it
        // ends (no gap week in between, so every week generated between historyStart and the
        // latest transaction has a transaction and a known regime).
        val termWeekStart = LocalDate.of(2026, 10, 5).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val holidayWeekStart = termWeekStart.plusDays(14)
        val terms = listOf(TermPeriod(id = 1, name = "Autumn", startDate = termWeekStart, endDate = termWeekStart.plusDays(13)))
        val transactions = listOf(
            tx(pool.id, groceries.id, termWeekStart, -4000),
            tx(pool.id, groceries.id, termWeekStart.plusDays(7), -6000), // still inside the term
            tx(pool.id, groceries.id, holidayWeekStart.plusDays(6), -1000), // Sunday of the holiday week
        )
        val averages = Trends.termVsHoliday(accounts, categories, transactions, terms, historyStart = termWeekStart)

        val term = averages.single { it.categoryId == groceries.id && it.regime == io.github.matteopi.budgeting.core.model.Regime.TERM }
        assertEquals(2, term.weeksOfData)
        assertEquals(Money(-5000), term.averageWeekly) // (-4000 + -6000) / 2

        val holiday = averages.single { it.categoryId == groceries.id && it.regime == io.github.matteopi.budgeting.core.model.Regime.HOLIDAY }
        assertEquals(1, holiday.weeksOfData)
        assertEquals(Money(-1000), holiday.averageWeekly)
    }

    @Test
    fun `balanceHistory is a cumulative running total of all transaction kinds on pool accounts`() {
        val start = LocalDate.of(2026, 9, 1)
        val transactions = listOf(
            tx(pool.id, null, start.minusDays(1), 100000, kind = TxKind.OPENING_BALANCE),
            tx(pool.id, groceries.id, start, -2000),
            tx(pool.id, groceries.id, start.plusDays(2), -500),
            tx(outsidePool.id, groceries.id, start.plusDays(2), -999999), // ignored, wrong account
        )
        val history = Trends.balanceHistory(accounts, transactions, start, start.plusDays(3))

        assertEquals(4, history.size)
        assertEquals(start to Money(98000), history[0])
        assertEquals(start.plusDays(1) to Money(98000), history[1])
        assertEquals(start.plusDays(2) to Money(97500), history[2])
        assertEquals(start.plusDays(3) to Money(97500), history[3])
    }

    @Test
    fun `monthlyInOut splits positive and negative totals per calendar month`() {
        val today = LocalDate.of(2026, 9, 15)
        val transactions = listOf(
            tx(pool.id, groceries.id, LocalDate.of(2026, 8, 5), -3000),
            tx(pool.id, untracked.id, LocalDate.of(2026, 8, 10), 150000), // some income
            tx(pool.id, groceries.id, LocalDate.of(2026, 9, 5), -4000),
        )
        val months = Trends.monthlyInOut(accounts, categories, transactions, months = 2, today = today)

        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9)), months.map { it.month })
        val august = months[0]
        assertEquals(Money(150000), august.totalIn)
        assertEquals(Money(-3000), august.totalOut)
        val september = months[1]
        assertEquals(Money.ZERO, september.totalIn)
        assertEquals(Money(-4000), september.totalOut)
    }

    @Test
    fun `empty ranges yield empty results`() {
        assertTrue(Trends.weeklyByCategory(accounts, categories, emptyList(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1)).isEmpty())
        assertTrue(Trends.balanceHistory(accounts, emptyList(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1)).isEmpty())
        assertTrue(Trends.termVsHoliday(accounts, categories, emptyList(), emptyList(), null).isEmpty())
    }
}
