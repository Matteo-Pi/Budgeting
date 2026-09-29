package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.forecast.AccountSplit
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BudgetSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultAccountTest {
    private fun account(id: Long, archived: Boolean = false, inPool: Boolean = true, type: AccountType = AccountType.CURRENT) =
        Account(id = id, name = "Account $id", type = type, archived = archived, includeInSafeToSpend = inPool)

    @Test
    fun `the chosen account wins when it is live`() {
        val accounts = listOf(account(1), account(2), account(3))
        assertEquals(2L, DefaultAccount.resolveId(accounts, configuredId = 2))
        assertEquals(accounts[1], DefaultAccount.resolve(accounts, configuredId = 2))
    }

    @Test
    fun `with no choice the default is the first account created, whatever the list order or type`() {
        val savings = account(5, type = AccountType.SAVINGS)
        val accounts = listOf(account(9), savings, account(7))
        assertEquals(5L, DefaultAccount.resolveId(accounts, configuredId = null))
    }

    @Test
    fun `a choice that points at a missing account is ignored`() {
        assertEquals(1L, DefaultAccount.resolveId(listOf(account(1), account(2)), configuredId = 99))
    }

    @Test
    fun `a choice that points at an archived account is ignored`() {
        val accounts = listOf(account(1), account(2, archived = true), account(3))
        assertEquals(1L, DefaultAccount.resolveId(accounts, configuredId = 2))
    }

    @Test
    fun `archived accounts are never the fallback`() {
        val accounts = listOf(account(1, archived = true), account(2), account(3))
        assertEquals(2L, DefaultAccount.resolveId(accounts, configuredId = null))
    }

    @Test
    fun `there is no default when there is no live account`() {
        assertNull(DefaultAccount.resolveId(emptyList(), configuredId = null))
        assertNull(DefaultAccount.resolveId(listOf(account(1, archived = true)), configuredId = 1))
        assertNull(DefaultAccount.resolve(emptyList(), configuredId = 3))
    }

    @Test
    fun `the default can be an account outside the pool`() {
        val accounts = listOf(account(1), account(2, inPool = false))
        assertEquals(2L, DefaultAccount.resolveId(accounts, configuredId = 2))
    }

    @Test
    fun `it agrees with the forecast engine's own default in the ordinary cases`() {
        val cases = listOf(
            listOf(account(1), account(2), account(3)) to null,
            listOf(account(1), account(2), account(3)) to 2L,
            listOf(account(1, archived = true), account(2), account(3)) to 1L,
            listOf(account(1), account(2, archived = true)) to 2L,
        )
        for ((accounts, configured) in cases) {
            assertEquals(
                AccountSplit.defaultAccountId(accounts, BudgetSettings(defaultAccountId = configured)),
                DefaultAccount.resolveId(accounts, configured),
                "$accounts / $configured",
            )
        }
    }
}
