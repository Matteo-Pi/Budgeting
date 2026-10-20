package io.github.matteopi.budgeting.core.planner.reminders

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Task
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What a reminder is about; later stages add the reviews and habits (docs/PLAN.md §13.4). */
enum class ReminderKind { TASK }

/**
 * One reminder to post at [at]. [key] is stable for the same reminder and changes when what it reminds of changes
 * (a moved date, a repeat that moved on), so it doubles as the notification's identity.
 */
data class Reminder(
    val key: String,
    val kind: ReminderKind,
    val itemId: Long,
    val at: Instant,
    val title: String,
    val text: String,
)

/**
 * When the Planner's reminders fire (docs/PLAN.md §13.4). The app keeps a single alarm, for [next]; when it
 * goes off it posts [dueBetween] the last time it posted and now.
 */
object ReminderPlan {

    /** A reminder more than this late (the phone was off) is dropped rather than posted. */
    val LATE_LIMIT: Duration = Duration.ofHours(12)

    /** The default reminder time for tasks without a time of their own. */
    val DEFAULT_TASK_TIME: LocalTime = LocalTime.of(9, 0)

    /**
     * The reminders of open [tasks] with a date and a reminder: the due moment ([Task.dueTime] on the date, or
     * [defaultTime]) minus [Task.reminderMinutesBefore].
     */
    fun taskReminders(tasks: List<Task>, defaultTime: LocalTime, zone: ZoneId, today: LocalDate): List<Reminder> =
        tasks.mapNotNull { task ->
            val date = task.dueDate ?: return@mapNotNull null
            val before = task.reminderMinutesBefore ?: return@mapNotNull null
            if (!task.isOpen) return@mapNotNull null
            val due = ZonedDateTime.of(date, task.dueTime ?: defaultTime, zone).toInstant()
            Reminder(
                key = "task:${task.id}:$date:${task.dueTime ?: "day"}:$before",
                kind = ReminderKind.TASK,
                itemId = task.id,
                at = due.minus(Duration.ofMinutes(before.toLong())),
                title = task.title,
                text = PlannerText.due(date, task.dueTime, today),
            )
        }

    /** The earliest reminder after [now], or null. */
    fun next(reminders: List<Reminder>, now: Instant): Reminder? =
        reminders.filter { it.at.isAfter(now) }.minWithOrNull(compareBy<Reminder> { it.at }.thenBy { it.key })

    /**
     * The reminders to post when the alarm goes off at [now]: due at or before [now], after [lastFired] (each fires
     * once), and not more than [LATE_LIMIT] late. With no [lastFired] (the first run), anything within the limit.
     */
    fun dueBetween(reminders: List<Reminder>, lastFired: Instant?, now: Instant): List<Reminder> =
        reminders.filter { r ->
            !r.at.isAfter(now) &&
                (lastFired == null || r.at.isAfter(lastFired)) &&
                Duration.between(r.at, now) <= LATE_LIMIT
        }.sortedWith(compareBy<Reminder> { it.at }.thenBy { it.key })
}
