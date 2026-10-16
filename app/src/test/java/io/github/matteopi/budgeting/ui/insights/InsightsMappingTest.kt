package io.github.matteopi.budgeting.ui.insights

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class InsightsMappingTest {
    private val from = LocalDate.of(2026, 9, 20)
    private val to = LocalDate.of(2026, 9, 28)

    private val current = Account(id = 1, name = "Current", type = AccountType.CURRENT, overdraftLimit = Money.pounds(200))
    private val savings = Account(id = 2, name = "Savings", type = AccountType.SAVINGS, overdraftLimit = Money.pounds(50))
    private val cash = Account(id = 3, name = "Cash", type = AccountType.CASH, includeInSafeToSpend = false)
    private val archived = Account(id = 4, name = "Old", type = AccountType.CURRENT, archived = true)
    private val accounts = listOf(current, savings, cash, archived)

    private fun tx(accountId: Long, pounds: Long, date: LocalDate, id: Long, kind: TxKind = TxKind.NORMAL) = Transaction(
        id = id, accountId = accountId, amount = Money.pounds(pounds), date = date, categoryId = null, kind = kind,
    )

    private val transactions = listOf(
        tx(1, 1_000, LocalDate.of(2026, 9, 1), id = 1, kind = TxKind.OPENING_BALANCE),
        tx(2, 500, LocalDate.of(2026, 9, 1), id = 2, kind = TxKind.OPENING_BALANCE),
        tx(3, 20, LocalDate.of(2026, 9, 1), id = 3, kind = TxKind.OPENING_BALANCE),
        tx(1, -100, LocalDate.of(2026, 9, 25), id = 4),
        tx(2, 30, LocalDate.of(2026, 9, 26), id = 5),
        tx(3, -5, LocalDate.of(2026, 9, 27), id = 6),
    )

    private fun valueOn(line: BalanceHistoryLine, date: LocalDate): Money = line.points.first { it.first == date }.second

    @Test
    fun `the pool is one line of the safe-to-spend accounts`() {
        val lines = InsightsMapping.balanceHistory(AccountView.Pool, accounts, transactions, from, to, ZeroPoint.ACCOUNT_BALANCE)
        assertEquals(1, lines.size)
        assertEquals("Safe-to-spend accounts", lines[0].label)
        assertEquals(null, lines[0].accountId)
        assertEquals(9, lines[0].points.size)
        // Pool = Current + Savings (Cash is outside it): £1,000 + £500 on the 24th, less £100 from the 25th, plus £30 from the 26th.
        assertEquals(Money.pounds(1_500), valueOn(lines[0], LocalDate.of(2026, 9, 24)))
        assertEquals(Money.pounds(1_400), valueOn(lines[0], LocalDate.of(2026, 9, 25)))
        assertEquals(Money.pounds(1_430), valueOn(lines[0], LocalDate.of(2026, 9, 28)))
    }

    @Test
    fun `all accounts is one line that also counts the accounts outside the pool`() {
        val lines = InsightsMapping.balanceHistory(AccountView.All, accounts, transactions, from, to, ZeroPoint.ACCOUNT_BALANCE)
        assertEquals(1, lines.size)
        assertEquals("All accounts", lines[0].label)
        assertEquals(null, lines[0].accountId)
        assertEquals(9, lines[0].points.size)
        // The pool's line plus Cash: £20 on the 24th, £15 from the 27th. The archived account counts for nothing.
        assertEquals(Money.pounds(1_520), valueOn(lines[0], LocalDate.of(2026, 9, 24)))
        assertEquals(Money.pounds(1_420), valueOn(lines[0], LocalDate.of(2026, 9, 25)))
        assertEquals(Money.pounds(1_445), valueOn(lines[0], LocalDate.of(2026, 9, 28)))
    }

    @Test
    fun `all accounts is the pool line when every account is in the pool`() {
        val everyAccount = accounts.map { it.copy(includeInSafeToSpend = true) }
        val all = InsightsMapping.balanceHistory(AccountView.All, everyAccount, transactions, from, to, ZeroPoint.ACCOUNT_BALANCE).single()
        val pool = InsightsMapping.balanceHistory(AccountView.Pool, everyAccount, transactions, from, to, ZeroPoint.ACCOUNT_BALANCE).single()
        assertEquals(pool.points, all.points)
    }

    @Test
    fun `each total adds the matching overdrafts for the overdraft zero point`() {
        val pool = InsightsMapping.balanceHistory(AccountView.Pool, accounts, transactions, from, to, ZeroPoint.OVERDRAFT_LIMIT)
        assertEquals(Money.pounds(1_500 + 250), valueOn(pool[0], LocalDate.of(2026, 9, 24)))
        val all = InsightsMapping.balanceHistory(AccountView.All, accounts, transactions, from, to, ZeroPoint.OVERDRAFT_LIMIT)
        // Cash has no overdraft, so only its £20 is added.
        assertEquals(Money.pounds(1_520 + 250), valueOn(all[0], LocalDate.of(2026, 9, 24)))
    }

    @Test
    fun `one account uses only its own transactions, even outside the pool`() {
        val savingsLine = InsightsMapping.balanceHistory(AccountView.Single(2), accounts, transactions, from, to, ZeroPoint.ACCOUNT_BALANCE).single()
        assertEquals("Savings", savingsLine.label)
        assertEquals(2L, savingsLine.accountId)
        assertEquals(Money.pounds(500), valueOn(savingsLine, LocalDate.of(2026, 9, 25)))
        assertEquals(Money.pounds(530), valueOn(savingsLine, LocalDate.of(2026, 9, 26)))

        val cashLine = InsightsMapping.balanceHistory(AccountView.Single(3), accounts, transactions, from, to, ZeroPoint.ACCOUNT_BALANCE).single()
        assertEquals(Money.pounds(15), valueOn(cashLine, LocalDate.of(2026, 9, 28)))
    }

    @Test
    fun `one account is converted with its own overdraft`() {
        val line = InsightsMapping.balanceHistory(AccountView.Single(1), accounts, transactions, from, to, ZeroPoint.OVERDRAFT_LIMIT).single()
        assertEquals(Money.pounds(900 + 200), valueOn(line, LocalDate.of(2026, 9, 28)))
    }

    @Test
    fun `each account gives one line per non-archived account in creation order`() {
        val lines = InsightsMapping.balanceHistory(AccountView.Each, accounts, transactions, from, to, ZeroPoint.ACCOUNT_BALANCE)
        assertEquals(listOf("Current", "Savings", "Cash"), lines.map { it.label })
        assertEquals(Money.pounds(900), valueOn(lines[0], to))
        assertEquals(Money.pounds(530), valueOn(lines[1], to))
        assertEquals(Money.pounds(15), valueOn(lines[2], to))
    }

    @Test
    fun `a missing or archived single account falls back to the all-accounts line`() {
        for (id in listOf(4L, 99L)) {
            val lines = InsightsMapping.balanceHistory(AccountView.Single(id), accounts, transactions, from, to, ZeroPoint.ACCOUNT_BALANCE)
            assertEquals(listOf("All accounts"), lines.map { it.label })
        }
    }

    @Test
    fun `each with no live accounts falls back to the all-accounts line`() {
        val lines = InsightsMapping.balanceHistory(AccountView.Each, listOf(archived), emptyList(), from, to, ZeroPoint.ACCOUNT_BALANCE)
        assertEquals(listOf("All accounts"), lines.map { it.label })
    }
}
