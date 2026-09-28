package io.github.matteopi.budgeting.work

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Whether [DailyWorker] should post the weekly "check your balances" reminder today
 * (docs/PLAN.md §6.8): the reminder is on, today is the chosen day, and no pool account has
 * been reconciled in the last 6 days.
 */
object ReminderRule {
    private const val FRESH_RECONCILIATION_DAYS = 6

    fun shouldRemind(
        enabled: Boolean,
        reminderDay: DayOfWeek,
        today: LocalDate,
        lastPoolReconciliationDate: LocalDate?,
    ): Boolean {
        if (!enabled) return false
        if (today.dayOfWeek != reminderDay) return false
        val daysSince = lastPoolReconciliationDate?.let { ChronoUnit.DAYS.between(it, today) }
        return daysSince == null || daysSince > FRESH_RECONCILIATION_DAYS
    }
}
