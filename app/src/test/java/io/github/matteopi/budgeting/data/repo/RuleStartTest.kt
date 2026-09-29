package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.forecast.Recurrence
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleStartTest {
    private val today = LocalDate.of(2026, 9, 29)

    private fun rule(start: LocalDate, postedThrough: LocalDate? = null) = RecurringRule(
        label = "Rent",
        accountId = 1,
        categoryId = 10,
        amount = Money.pounds(-500),
        frequency = Frequency.MONTHLY,
        startDate = start,
        postingMode = PostingMode.AUTO,
        postedThrough = postedThrough,
    )

    @Test
    fun `a rule starting in the past is posted through yesterday so past payments are not re-posted`() {
        val stored = RuleStart.withInitialPostedThrough(rule(start = LocalDate.of(2026, 9, 1)), today)
        assertEquals(today.minusDays(1), stored.postedThrough)
    }

    @Test
    fun `a rule starting today is left untouched so today's payment is posted`() {
        assertNull(RuleStart.withInitialPostedThrough(rule(start = today), today).postedThrough)
    }

    @Test
    fun `a past-start rule whose payment falls today still posts today's payment`() {
        // Monthly on the same day of the month as today, started months ago.
        val stored = RuleStart.withInitialPostedThrough(rule(start = today.minusMonths(2)), today)
        val from = stored.postedThrough!!.plusDays(1)
        assertEquals(listOf(today), Recurrence.occurrences(stored, from, today))
    }

    @Test
    fun `a rule starting in the future is left untouched`() {
        val future = rule(start = today.plusDays(1))
        assertNull(RuleStart.withInitialPostedThrough(future, today).postedThrough)
        assertEquals(future, RuleStart.withInitialPostedThrough(future, today))
    }

    @Test
    fun `a rule that already carries a posted-through date keeps it`() {
        val restored = rule(start = LocalDate.of(2026, 1, 1), postedThrough = LocalDate.of(2026, 8, 1))
        assertEquals(LocalDate.of(2026, 8, 1), RuleStart.withInitialPostedThrough(restored, today).postedThrough)
    }

    @Test
    fun `nothing is due after the rule is stored, and the next payment is the next one after today`() {
        val stored = RuleStart.withInitialPostedThrough(rule(start = LocalDate.of(2026, 9, 1)), today)
        // What PostingService.postDue would post: occurrences in (postedThrough, today].
        val from = stored.postedThrough!!.plusDays(1)
        assertTrue(Recurrence.occurrences(stored, from, today).isEmpty(), "no past occurrence may be posted")
        // The forecast still sees the future ones.
        assertEquals(listOf(LocalDate.of(2026, 10, 1)), Recurrence.occurrences(stored, from, LocalDate.of(2026, 10, 15)))
    }

    @Test
    fun `without the rule every past occurrence would have been posted`() {
        val unstored = rule(start = LocalDate.of(2026, 7, 1))
        val from = (unstored.postedThrough ?: unstored.startDate.minusDays(1)).plusDays(1)
        assertEquals(3, Recurrence.occurrences(unstored, from, today).size)
    }

    @Test
    fun `the past-start note applies only to start dates before today`() {
        assertTrue(RuleStart.startsInPast(today.minusDays(1), today))
        assertFalse(RuleStart.startsInPast(today, today))
        assertFalse(RuleStart.startsInPast(today.plusDays(1), today))
        assertEquals("Payments before today are treated as already paid.", RuleStart.PAST_START_NOTE)
    }
}
