package io.github.matteopi.budgeting.data.settings

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountViewTest {
    private val current = Account(id = 1, name = "Current", type = AccountType.CURRENT)
    private val savings = Account(id = 2, name = "Savings", type = AccountType.SAVINGS)
    private val old = Account(id = 3, name = "Old", type = AccountType.CURRENT, archived = true)
    private val accounts = listOf(current, savings, old)

    @Test
    fun `encodes and parses every view`() {
        assertEquals("all", AccountView.encode(AccountView.All))
        assertEquals("pool", AccountView.encode(AccountView.Pool))
        assertEquals("each", AccountView.encode(AccountView.Each))
        assertEquals("account:12", AccountView.encode(AccountView.Single(12)))
        assertEquals(AccountView.All, AccountView.parseOrNull("all"))
        assertEquals(AccountView.Pool, AccountView.parseOrNull("pool"))
        assertEquals(AccountView.Each, AccountView.parseOrNull("each"))
        assertEquals(AccountView.Single(12), AccountView.parseOrNull("account:12"))
    }

    @Test
    fun `every view round-trips through its encoding`() {
        for (view in listOf(AccountView.All, AccountView.Pool, AccountView.Each, AccountView.Single(7))) {
            assertEquals(view, AccountView.parseOrNull(AccountView.encode(view)))
        }
    }

    @Test
    fun `the legacy total value is read as all accounts and never written`() {
        // "total" was always labelled "All accounts" in the app, so that is what the user chose.
        assertEquals(AccountView.All, AccountView.parseOrNull("total"))
        assertEquals(AccountView.All, AccountView.parseChart("total"))
        assertEquals(AccountView.All, AccountView.parseBalance("total"))
        assertEquals("all", AccountView.encode(AccountView.parseOrNull("total")!!))
    }

    @Test
    fun `unreadable values parse to null`() {
        assertNull(AccountView.parseOrNull(null))
        assertNull(AccountView.parseOrNull(""))
        assertNull(AccountView.parseOrNull("TOTAL"))
        assertNull(AccountView.parseOrNull("POOL"))
        assertNull(AccountView.parseOrNull("account:"))
        assertNull(AccountView.parseOrNull("account:abc"))
        assertNull(AccountView.parseOrNull("accounts:1"))
    }

    @Test
    fun `an unknown chart value is all accounts`() {
        assertEquals(AccountView.All, AccountView.parseChart(null))
        assertEquals(AccountView.All, AccountView.parseChart("sideways"))
        assertEquals(AccountView.Pool, AccountView.parseChart("pool"))
        assertEquals(AccountView.Each, AccountView.parseChart("each"))
        assertEquals(AccountView.Single(5), AccountView.parseChart("account:5"))
    }

    @Test
    fun `a balance value is never each and an unknown one is all accounts`() {
        assertEquals(AccountView.All, AccountView.parseBalance("each"))
        assertEquals(AccountView.All, AccountView.parseBalance(null))
        assertEquals(AccountView.All, AccountView.parseBalance("nonsense"))
        assertEquals(AccountView.Pool, AccountView.parseBalance("pool"))
        assertEquals(AccountView.Single(5), AccountView.parseBalance("account:5"))
    }

    @Test
    fun `a view pointing at a missing account falls back to all accounts`() {
        assertEquals(AccountView.All, AccountView.Single(99).resolveForChart(accounts))
        assertEquals(AccountView.All, AccountView.Single(99).resolveForBalance(accounts))
    }

    @Test
    fun `a view pointing at an archived account falls back to all accounts`() {
        assertEquals(AccountView.All, AccountView.Single(3).resolveForChart(accounts))
        assertEquals(AccountView.All, AccountView.Single(3).resolveForBalance(accounts))
    }

    @Test
    fun `a view pointing at a live account is kept`() {
        assertEquals(AccountView.Single(2), AccountView.Single(2).resolveForChart(accounts))
        assertEquals(AccountView.Single(2), AccountView.Single(2).resolveForBalance(accounts))
    }

    @Test
    fun `each is kept for charts and becomes all accounts for balances`() {
        assertEquals(AccountView.Each, AccountView.Each.resolveForChart(accounts))
        assertEquals(AccountView.All, AccountView.Each.resolveForBalance(accounts))
    }

    @Test
    fun `all accounts and the safe-to-spend accounts always stay as they are`() {
        assertEquals(AccountView.All, AccountView.All.resolveForChart(emptyList()))
        assertEquals(AccountView.All, AccountView.All.resolveForBalance(emptyList()))
        assertEquals(AccountView.Pool, AccountView.Pool.resolveForChart(emptyList()))
        assertEquals(AccountView.Pool, AccountView.Pool.resolveForBalance(emptyList()))
    }

    @Test
    fun `display names`() {
        assertEquals("All accounts", AccountView.All.displayName(accounts))
        assertEquals("Safe-to-spend accounts", AccountView.Pool.displayName(accounts))
        assertEquals("Each account", AccountView.Each.displayName(accounts))
        assertEquals("Savings", AccountView.Single(2).displayName(accounts))
        assertEquals("All accounts", AccountView.Single(99).displayName(accounts))
    }

    @Test
    fun `the pool differs from all accounts only when a live account is left out of it`() {
        assertFalse(poolDiffersFromAll(accounts))
        val cash = Account(id = 4, name = "Cash", type = AccountType.CASH, includeInSafeToSpend = false)
        assertTrue(poolDiffersFromAll(accounts + cash))
        // An archived account left out of the pool changes nothing: neither view counts it.
        assertFalse(poolDiffersFromAll(accounts + cash.copy(archived = true)))
    }

    @Test
    fun `the pool view collapses to all accounts when they are the same or there is nothing to choose`() {
        val cash = Account(id = 4, name = "Cash", type = AccountType.CASH, includeInSafeToSpend = false)
        assertEquals(AccountView.All, AccountView.Pool.collapsePool(accounts))
        assertEquals(AccountView.Pool, AccountView.Pool.collapsePool(accounts + cash))
        assertEquals(AccountView.All, AccountView.Pool.collapsePool(listOf(cash)))
        assertEquals(AccountView.Each, AccountView.Each.collapsePool(accounts))
        assertEquals(AccountView.All, AccountView.All.collapsePool(accounts + cash))
    }
}
