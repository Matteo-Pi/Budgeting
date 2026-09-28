package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BalancesTest {
    private val today = LocalDate.of(2024, 6, 1)

    @Test
    fun accountBalanceSumsOnlyOnOrBeforeAsOf() {
        val txs = listOf(
            TestData.tx(1, Money.pounds(100), today.minusDays(5), null),
            TestData.tx(1, Money.pounds(-20), today, null),
            TestData.tx(1, Money.pounds(999), today.plusDays(1), null), // future, excluded
            TestData.tx(2, Money.pounds(50), today, null), // different account
        )
        assertEquals(Money.pounds(80), Balances.accountBalance(1, txs, today))
    }

    @Test
    fun balancesByAccountGroupsEveryAccount() {
        val txs = listOf(
            TestData.tx(1, Money.pounds(10), today, null),
            TestData.tx(2, Money.pounds(20), today, null),
            TestData.tx(1, Money.pounds(5), today, null),
        )
        assertEquals(mapOf(1L to Money.pounds(15), 2L to Money.pounds(20)), Balances.balancesByAccount(txs, today))
    }

    @Test
    fun poolExcludesArchivedAndNotIncludedAccounts() {
        val accounts = listOf(
            Account(id = 1, name = "A", type = AccountType.CURRENT, includeInSafeToSpend = true, archived = false),
            Account(id = 2, name = "B", type = AccountType.CURRENT, includeInSafeToSpend = false, archived = false),
            Account(id = 3, name = "C", type = AccountType.CURRENT, includeInSafeToSpend = true, archived = true),
        )
        assertEquals(listOf(1L), Balances.pool(accounts).map { it.id })
    }

    @Test
    fun poolBalanceOnlySumsPoolAccounts() {
        val accounts = TestData.accounts()
        val txs = listOf(
            TestData.tx(TestData.CURRENT, Money.pounds(100), today, null),
            TestData.tx(TestData.SAVINGS, Money.pounds(50), today, null),
            TestData.tx(TestData.OUTSIDE_POOL, Money.pounds(9999), today, null),
        )
        assertEquals(Money.pounds(150), Balances.poolBalance(accounts, txs, today))
    }

    @Test
    fun poolOverdraftSumsOverdraftLimitsInThePool() {
        assertEquals(Money.pounds(200), Balances.poolOverdraft(TestData.accounts()))
    }

    @Test
    fun floorDependsOnZeroPoint() {
        val accounts = TestData.accounts()
        assertEquals(Money.ZERO, Balances.floor(accounts, ZeroPoint.ACCOUNT_BALANCE))
        assertEquals(Money.pounds(-200), Balances.floor(accounts, ZeroPoint.OVERDRAFT_LIMIT))
    }

    @Test
    fun toDisplayAddsOverdraftOnlyForOverdraftLimitMode() {
        val raw = Money.pounds(-50)
        val overdraft = Money.pounds(200)
        assertEquals(Money.pounds(-50), Balances.toDisplay(raw, overdraft, ZeroPoint.ACCOUNT_BALANCE))
        assertEquals(Money.pounds(150), Balances.toDisplay(raw, overdraft, ZeroPoint.OVERDRAFT_LIMIT))
    }
}
