package io.github.matteopi.budgeting.ui.home

import io.github.matteopi.budgeting.core.model.Reconciliation
import java.time.LocalDate

/**
 * Pure rule for Home's "check your balance" nudge (docs/PLAN.md §6.5): shown when no pool
 * account's balance has been checked against the bank in the last week.
 */
object ReconcileNudge {
    const val STALE_DAYS = 7L

    /**
     * True when the most recent balance check of any account in [poolAccountIds] is more than
     * [STALE_DAYS] days before [today], or there is none at all. A check is a reconciliation or
     * an account's opening balance ([openingBalanceDates]: the balance typed in from the bank app
     * when the account was set up), so a brand-new user isn't told they haven't checked in a while.
     */
    fun isStale(
        reconciliations: List<Reconciliation>,
        poolAccountIds: Set<Long>,
        today: LocalDate,
        openingBalanceDates: List<LocalDate> = emptyList(),
    ): Boolean {
        if (poolAccountIds.isEmpty()) return false
        val lastReconciled = reconciliations
            .asSequence()
            .filter { it.accountId in poolAccountIds }
            .maxOfOrNull { it.date }
        val mostRecent = listOfNotNull(lastReconciled, openingBalanceDates.maxOrNull()).maxOrNull() ?: return true
        return mostRecent.isBefore(today.minusDays(STALE_DAYS))
    }
}
