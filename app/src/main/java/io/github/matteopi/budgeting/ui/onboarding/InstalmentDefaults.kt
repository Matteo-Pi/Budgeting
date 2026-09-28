package io.github.matteopi.budgeting.ui.onboarding

import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate

/**
 * Default dates for the three student-loan instalments offered in the Money in step: the start
 * of the next three terms entered so far (docs/PLAN.md §6.5), falling back to [today] for any
 * instalments with no upcoming term to anchor to (mirrors
 * [io.github.matteopi.budgeting.ui.plan.StudentLoanHelperViewModel], which the full Plan screen
 * uses for the same helper once onboarding is done).
 */
object InstalmentDefaults {
    fun defaultDates(terms: List<TermPeriod>, today: LocalDate): List<LocalDate> {
        val nextStarts = terms
            .filter { it.startDate.isAfter(today) }
            .sortedBy { it.startDate }
            .map { it.startDate }
            .take(3)
        return (nextStarts + List(3) { today }).take(3)
    }
}
