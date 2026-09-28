package io.github.matteopi.budgeting.ui.home

import io.github.matteopi.budgeting.core.model.Reconciliation
import java.time.LocalDate

/**
 * Pure rule for Home's "check your balance" nudge (docs/PLAN.md §6.5): shown when no pool
 * account has been reconciled in the last week.
 */
object ReconcileNudge {
    const val STALE_DAYS = 7L

    /**
     * True when the most recent reconciliation of any account in [poolAccountIds] is more than
     * [STALE_DAYS] days before [today], or there is none at all.
     */
    fun isStale(
        reconciliations: List<Reconciliation>,
        poolAccountIds: Set<Long>,
        today: LocalDate,
    ): Boolean {
        if (poolAccountIds.isEmpty()) return false
        val mostRecent = reconciliations
            .asSequence()
            .filter { it.accountId in poolAccountIds }
            .maxOfOrNull { it.date }
            ?: return true
        return mostRecent.isBefore(today.minusDays(STALE_DAYS))
    }
}
