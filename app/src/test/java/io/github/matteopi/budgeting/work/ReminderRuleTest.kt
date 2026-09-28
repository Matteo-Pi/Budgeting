package io.github.matteopi.budgeting.work

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderRuleTest {
    private val sunday = LocalDate.of(2026, 9, 27) // a Sunday
    private val monday = LocalDate.of(2026, 9, 28) // a Monday

    @Test
    fun `disabled never reminds`() {
        assertFalse(
            ReminderRule.shouldRemind(
                enabled = false,
                reminderDay = DayOfWeek.SUNDAY,
                today = sunday,
                lastPoolReconciliationDate = null,
            ),
        )
    }

    @Test
    fun `wrong day of week never reminds`() {
        assertFalse(
            ReminderRule.shouldRemind(
                enabled = true,
                reminderDay = DayOfWeek.SUNDAY,
                today = monday,
                lastPoolReconciliationDate = null,
            ),
        )
    }

    @Test
    fun `right day and never reconciled reminds`() {
        assertTrue(
            ReminderRule.shouldRemind(
                enabled = true,
                reminderDay = DayOfWeek.SUNDAY,
                today = sunday,
                lastPoolReconciliationDate = null,
            ),
        )
    }

    @Test
    fun `right day but reconciled recently does not remind`() {
        assertFalse(
            ReminderRule.shouldRemind(
                enabled = true,
                reminderDay = DayOfWeek.SUNDAY,
                today = sunday,
                lastPoolReconciliationDate = sunday.minusDays(3),
            ),
        )
    }

    @Test
    fun `exactly 6 days since reconciliation does not remind`() {
        assertFalse(
            ReminderRule.shouldRemind(
                enabled = true,
                reminderDay = DayOfWeek.SUNDAY,
                today = sunday,
                lastPoolReconciliationDate = sunday.minusDays(6),
            ),
        )
    }

    @Test
    fun `more than 6 days since reconciliation reminds`() {
        assertTrue(
            ReminderRule.shouldRemind(
                enabled = true,
                reminderDay = DayOfWeek.SUNDAY,
                today = sunday,
                lastPoolReconciliationDate = sunday.minusDays(7),
            ),
        )
    }
}
