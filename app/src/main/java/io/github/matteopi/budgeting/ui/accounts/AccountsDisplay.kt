package io.github.matteopi.budgeting.ui.accounts

import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint

/**
 * Pure display-balance mapping shared by Home, the accounts list and Account detail
 * (docs/PLAN.md §6.5 point 5: balances always respect the zero-point setting).
 */
object AccountsDisplay {
    /** [account]'s balance in the user's chosen "£0" terms, converted using its own overdraft limit. */
    fun displayBalance(account: Account, rawBalancesById: Map<Long, Money>, zeroPoint: ZeroPoint): Money {
        val raw = rawBalancesById[account.id] ?: Money.ZERO
        return Balances.toDisplay(raw, account.overdraftLimit, zeroPoint)
    }
}
