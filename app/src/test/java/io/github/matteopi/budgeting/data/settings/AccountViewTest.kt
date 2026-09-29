package io.github.matteopi.budgeting.data.settings

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountViewTest {
    private val current = Account(id = 1, name = "Current", type = AccountType.CURRENT)
    private val savings = Account(id = 2, name = "Savings", type = AccountType.SAVINGS)
    private val old = Account(id = 3, name = "Old", type = AccountType.CURRENT, archived = true)
    private val accounts = listOf(current, savings, old)

    @Test
    fun `encodes and parses every view`() {
        assertEquals("total", AccountView.encode(AccountView.Total))
        assertEquals("each", AccountView.encode(AccountView.Each))
        assertEquals("account:12", AccountView.encode(AccountView.Single(12)))
        assertEquals(AccountView.Total, AccountView.parseOrNull("total"))
        assertEquals(AccountView.Each, AccountView.parseOrNull("each"))
        assertEquals(AccountView.Single(12), AccountView.parseOrNull("account:12"))
    }

    @Test
    fun `unreadable values parse to null`() {
        assertNull(AccountView.parseOrNull(null))
        assertNull(AccountView.parseOrNull(""))
        assertNull(AccountView.parseOrNull("TOTAL"))
        assertNull(AccountView.parseOrNull("account:"))
        assertNull(AccountView.parseOrNull("account:abc"))
        assertNull(AccountView.parseOrNull("accounts:1"))
    }

    @Test
    fun `an unknown chart value is the total`() {
        assertEquals(AccountView.Total, AccountView.parseChart(null))
        assertEquals(AccountView.Total, AccountView.parseChart("sideways"))
        assertEquals(AccountView.Each, AccountView.parseChart("each"))
        assertEquals(AccountView.Single(5), AccountView.parseChart("account:5"))
    }

    @Test
    fun `a balance value is never each and an unknown one is the total`() {
        assertEquals(AccountView.Total, AccountView.parseBalance("each"))
        assertEquals(AccountView.Total, AccountView.parseBalance(null))
        assertEquals(AccountView.Total, AccountView.parseBalance("nonsense"))
        assertEquals(AccountView.Single(5), AccountView.parseBalance("account:5"))
    }

    @Test
    fun `a view pointing at a missing account falls back to the total`() {
        assertEquals(AccountView.Total, AccountView.Single(99).resolveForChart(accounts))
        assertEquals(AccountView.Total, AccountView.Single(99).resolveForBalance(accounts))
    }

    @Test
    fun `a view pointing at an archived account falls back to the total`() {
        assertEquals(AccountView.Total, AccountView.Single(3).resolveForChart(accounts))
        assertEquals(AccountView.Total, AccountView.Single(3).resolveForBalance(accounts))
    }

    @Test
    fun `a view pointing at a live account is kept`() {
        assertEquals(AccountView.Single(2), AccountView.Single(2).resolveForChart(accounts))
        assertEquals(AccountView.Single(2), AccountView.Single(2).resolveForBalance(accounts))
    }

    @Test
    fun `each is kept for charts and becomes the total for balances`() {
        assertEquals(AccountView.Each, AccountView.Each.resolveForChart(accounts))
        assertEquals(AccountView.Total, AccountView.Each.resolveForBalance(accounts))
    }

    @Test
    fun `the total is always the total`() {
        assertEquals(AccountView.Total, AccountView.Total.resolveForChart(emptyList()))
        assertEquals(AccountView.Total, AccountView.Total.resolveForBalance(emptyList()))
    }
}
