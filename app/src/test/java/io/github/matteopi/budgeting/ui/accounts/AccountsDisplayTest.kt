package io.github.matteopi.budgeting.ui.accounts

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountsDisplayTest {

    private val account = Account(
        id = 1,
        name = "Current",
        type = AccountType.CURRENT,
        overdraftLimit = Money.pounds(250),
    )

    @Test
    fun `account balance shows raw when zero point is account balance`() {
        val balances = mapOf(1L to Money.pounds(-50))
        assertEquals(Money.pounds(-50), AccountsDisplay.displayBalance(account, balances, ZeroPoint.ACCOUNT_BALANCE))
    }

    @Test
    fun `account balance adds its own overdraft limit when zero point is overdraft limit`() {
        val balances = mapOf(1L to Money.pounds(-50))
        assertEquals(Money.pounds(200), AccountsDisplay.displayBalance(account, balances, ZeroPoint.OVERDRAFT_LIMIT))
    }

    @Test
    fun `missing balance defaults to zero`() {
        assertEquals(Money.ZERO, AccountsDisplay.displayBalance(account, emptyMap(), ZeroPoint.ACCOUNT_BALANCE))
        assertEquals(Money.pounds(250), AccountsDisplay.displayBalance(account, emptyMap(), ZeroPoint.OVERDRAFT_LIMIT))
    }
}
