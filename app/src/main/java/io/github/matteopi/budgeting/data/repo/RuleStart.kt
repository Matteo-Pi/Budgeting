package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.RecurringRule
import java.time.LocalDate

/**
 * How a new recurring rule treats occurrences that fall before it was created.
 *
 * A rule created with a start date in the past ("Rent, monthly on the 1st" added on the 29th)
 * describes payments the user's opening balance already includes. If `postedThrough` were left
 * null, `PostingService.postDue` would post every past occurrence again as a brand-new transaction
 * (double counting the money), and a CONFIRM rule would ask about every one of them. So on insert
 * everything up to and including today counts as already dealt with.
 */
object RuleStart {
    /** Shown in the Recurring editor when the start date is in the past. */
    const val PAST_START_NOTE = "Payments before today are treated as already paid."

    /** True when [startDate] is before [today], i.e. the rule has occurrences that will be treated as already paid. */
    fun startsInPast(startDate: LocalDate, today: LocalDate): Boolean = startDate.isBefore(today)

    /**
     * [rule] as it should be first stored: a rule with no `postedThrough` that starts before [today]
     * is marked as posted through yesterday — its earlier payments are already in the opening
     * balance, so they must not be posted again — while a payment due today is still posted,
     * matching [PAST_START_NOTE]. A rule that already carries a `postedThrough` (a backup restore,
     * say) or starts today or later is left exactly as it is.
     */
    fun withInitialPostedThrough(rule: RecurringRule, today: LocalDate): RecurringRule =
        if (rule.postedThrough == null && rule.startDate.isBefore(today)) rule.copy(postedThrough = today.minusDays(1)) else rule
}
