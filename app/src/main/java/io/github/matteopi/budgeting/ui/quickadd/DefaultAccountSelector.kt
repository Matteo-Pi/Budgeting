package io.github.matteopi.budgeting.ui.quickadd

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType

/**
 * Chooses the account a new quick-add transaction defaults to (docs/PLAN.md §6.6): the
 * settings default account if it still exists, else the first CURRENT account, else the
 * first account of any type. Null if there are no accounts.
 */
object DefaultAccountSelector {
    fun choose(accounts: List<Account>, defaultAccountId: Long?): Long? {
        if (defaultAccountId != null && accounts.any { it.id == defaultAccountId }) return defaultAccountId
        return accounts.firstOrNull { it.type == AccountType.CURRENT }?.id ?: accounts.firstOrNull()?.id
    }
}
