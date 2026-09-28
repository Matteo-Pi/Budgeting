package io.github.matteopi.budgeting.ui.quickadd

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultAccountSelectorTest {
    private val current = Account(id = 1, name = "Current", type = AccountType.CURRENT)
    private val savings = Account(id = 2, name = "Savings", type = AccountType.SAVINGS)
    private val secondCurrent = Account(id = 3, name = "Second current", type = AccountType.CURRENT)

    @Test
    fun prefersTheSettingsDefault() {
        val accounts = listOf(savings, current)
        assertEquals(2, DefaultAccountSelector.choose(accounts, defaultAccountId = 2))
    }

    @Test
    fun ignoresAMissingSettingsDefault() {
        val accounts = listOf(savings, current)
        assertEquals(1, DefaultAccountSelector.choose(accounts, defaultAccountId = 999))
    }

    @Test
    fun fallsBackToTheFirstCurrentAccount() {
        val accounts = listOf(savings, secondCurrent)
        assertEquals(3, DefaultAccountSelector.choose(accounts, defaultAccountId = null))
    }

    @Test
    fun fallsBackToTheFirstAccountWhenNoneIsCurrent() {
        val accounts = listOf(savings)
        assertEquals(2, DefaultAccountSelector.choose(accounts, defaultAccountId = null))
    }

    @Test
    fun nullWhenThereAreNoAccounts() {
        assertNull(DefaultAccountSelector.choose(emptyList(), defaultAccountId = null))
    }
}
