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
 * everything up to and including today counts as already dealt with. That includes a payment due
 * today, the default start date: the balance the user just entered comes from their bank app,
 * which usually already shows it, and posting it again would count it twice.
 */
object RuleStart {
    /** Shown in the Recurring editor when the start date is in the past (the preview shows the next payment). */
    const val PAST_START_NOTE = "Payments up to and including today are treated as already paid."

    /** True when [startDate] is before [today], i.e. the rule has occurrences that will be treated as already paid. */
    fun startsInPast(startDate: LocalDate, today: LocalDate): Boolean = startDate.isBefore(today)

    /**
     * [rule] as it should be first stored: a rule with no `postedThrough` that starts on or before
     * [today] is marked as posted through [today]; a rule that already carries a `postedThrough`
     * (a backup restore, say) or starts in the future is left exactly as it is.
     */
    fun withInitialPostedThrough(rule: RecurringRule, today: LocalDate): RecurringRule =
        if (rule.postedThrough == null && !rule.startDate.isAfter(today)) rule.copy(postedThrough = today) else rule
}
