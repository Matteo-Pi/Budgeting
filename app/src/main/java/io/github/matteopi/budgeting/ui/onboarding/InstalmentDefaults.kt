package io.github.matteopi.budgeting.ui.onboarding

import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate

/**
 * Default dates for the three student-loan instalments offered in the Money in step and in the
 * Plan screen's helper: the start of the next three terms entered so far (docs/PLAN.md §6.5).
 * An instalment with no upcoming term to anchor to gets `null` — it stays blank until the person
 * picks a date, rather than defaulting to today (which would create a fake "did your loan
 * arrive?" prompt).
 */
object InstalmentDefaults {
    fun defaultDates(terms: List<TermPeriod>, today: LocalDate): List<LocalDate?> {
        val nextStarts = terms
            .filter { it.startDate.isAfter(today) }
            .sortedBy { it.startDate }
            .map { it.startDate }
            .take(3)
        return (nextStarts + List(3) { null }).take(3)
    }
}
