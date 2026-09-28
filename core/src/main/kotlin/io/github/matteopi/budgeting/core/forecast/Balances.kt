package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.core.model.sum
import java.time.LocalDate

/** Balance arithmetic over the ledger and the "pool" of accounts counted for forecasting. See `docs/PLAN.md` §5.4. */
object Balances {
    /** Sums every transaction (any kind) on [accountId] dated on or before [asOf]. */
    fun accountBalance(accountId: Long, transactions: List<Transaction>, asOf: LocalDate): Money =
        transactions.asSequence()
            .filter { it.accountId == accountId && !it.date.isAfter(asOf) }
            .map { it.amount }
            .toList()
            .sum()

    fun balancesByAccount(transactions: List<Transaction>, asOf: LocalDate): Map<Long, Money> {
        val totals = HashMap<Long, Long>()
        for (tx in transactions) {
            if (tx.date.isAfter(asOf)) continue
            totals[tx.accountId] = (totals[tx.accountId] ?: 0L) + tx.amount.pence
        }
        return totals.mapValues { Money(it.value) }
    }

    /** Accounts counted towards safe-to-spend: included and not archived. */
    fun pool(accounts: List<Account>): List<Account> =
        accounts.filter { it.includeInSafeToSpend && !it.archived }

    fun poolBalance(accounts: List<Account>, transactions: List<Transaction>, asOf: LocalDate): Money {
        val poolIds = pool(accounts).map { it.id }.toSet()
        if (poolIds.isEmpty()) return Money.ZERO
        return transactions.asSequence()
            .filter { it.accountId in poolIds && !it.date.isAfter(asOf) }
            .map { it.amount }
            .toList()
            .sum()
    }

    /** Sum of overdraft limits over the pool (positive). */
    fun poolOverdraft(accounts: List<Account>): Money = pool(accounts).map { it.overdraftLimit }.sum()

    /** Raw-terms floor: 0 for ACCOUNT_BALANCE, or `-poolOverdraft` for OVERDRAFT_LIMIT. */
    fun floor(accounts: List<Account>, zeroPoint: ZeroPoint): Money = when (zeroPoint) {
        ZeroPoint.ACCOUNT_BALANCE -> Money.ZERO
        ZeroPoint.OVERDRAFT_LIMIT -> -poolOverdraft(accounts)
    }

    /** Converts a raw balance to the user's chosen "£0" display convention. */
    fun toDisplay(raw: Money, overdraft: Money, zeroPoint: ZeroPoint): Money = when (zeroPoint) {
        ZeroPoint.ACCOUNT_BALANCE -> raw
        ZeroPoint.OVERDRAFT_LIMIT -> raw + overdraft
    }
}
