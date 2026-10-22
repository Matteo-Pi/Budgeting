package io.github.matteopi.budgeting.core.planner.reminders

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.habits.HabitDayState
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.habits.HabitStatus
import io.github.matteopi.budgeting.core.planner.habits.HabitText
import io.github.matteopi.budgeting.core.planner.model.DayReview
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.Task
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * What a reminder is about (docs/PLAN.md §13.4). [WIDGET] posts nothing: it redraws the Planner widget when a class
 * starts or ends.
 */
enum class ReminderKind { TASK, DAY_REVIEW, WIDGET, HABIT, WEEKLY_REVIEW }

/**
 * One reminder to post at [at]. [key] is stable for the same reminder and changes when what it reminds of changes
 * (a moved date, a repeat that moved on), so it doubles as the notification's identity. [offersDone]: its notification
 * has a *Done* button (tasks, and habits that aren't limits).
 */
data class Reminder(
    val key: String,
    val kind: ReminderKind,
    val itemId: Long,
    val at: Instant,
    val title: String,
    val text: String,
    val offersDone: Boolean = false,
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
                offersDone = true,
            )
        }

    /**
     * The end-of-day review at [time] today and tomorrow (so the alarm moves on once today's has gone), leaving out
     * days whose review is finished. Null [time]: the review reminder is off. [itemId] is the date's epoch day.
     */
    fun dayReviewReminders(time: LocalTime?, reviews: List<DayReview>, today: LocalDate, zone: ZoneId): List<Reminder> {
        if (time == null) return emptyList()
        val finished = reviews.filter { it.finishedAt != null }.map { it.date }.toSet()
        return listOf(today, today.plusDays(1)).filter { it !in finished }.map { date ->
            Reminder(
                key = "review:$date:$time",
                kind = ReminderKind.DAY_REVIEW,
                itemId = date.toEpochDay(),
                at = ZonedDateTime.of(date, time, zone).toInstant(),
                title = "How did today go?",
                text = "Fill in the gaps, confirm what happened and finish the day.",
            )
        }
    }

    /**
     * The reminders of habits with a reminder time, today and tomorrow, on the days still to do: [HabitDayState.PENDING]
     * in each habit's status (which must cover both days). Archived habits have none.
     */
    fun habitReminders(statuses: List<HabitStatus>, today: LocalDate, zone: ZoneId): List<Reminder> =
        statuses.flatMap { status ->
            val habit = status.habit
            val time = habit.reminderTime
            if (time == null || habit.archived) return@flatMap emptyList()
            listOf(today, today.plusDays(1)).filter { status.day(it)?.state == HabitDayState.PENDING }.map { date ->
                Reminder(
                    key = "habit:${habit.id}:$date:$time",
                    kind = ReminderKind.HABIT,
                    itemId = habit.id,
                    at = ZonedDateTime.of(date, time, zone).toInstant(),
                    title = habit.name,
                    text = if (date == today) HabitText.progress(status) else HabitText.goal(habit.goal),
                    offersDone = HabitProgress.tickAmount(habit.goal) != null,
                )
            }
        }

    /**
     * The weekly review at [time] on [day], this week's and next week's (so the alarm moves on once this week's has
     * gone). Null [time]: the weekly review's reminder is off. [itemId] is the date's epoch day.
     */
    fun weeklyReviewReminders(day: DayOfWeek, time: LocalTime?, today: LocalDate, zone: ZoneId): List<Reminder> {
        if (time == null) return emptyList()
        val first = today.with(TemporalAdjusters.nextOrSame(day))
        return listOf(first, first.plusWeeks(1)).map { date ->
            Reminder(
                key = "weekly:$date:$time",
                kind = ReminderKind.WEEKLY_REVIEW,
                itemId = date.toEpochDay(),
                at = ZonedDateTime.of(date, time, zone).toInstant(),
                title = "Plan your week",
                text = "See how the week went and accept the study suggested for the next.",
            )
        }
    }

    /** The next class start and end after [now], when the widget's *next class* changes. */
    fun widgetRefreshes(occurrences: List<Occurrence>, now: Instant): List<Reminder> =
        occurrences.filter { !it.cancelled }.flatMap { listOf(it.start, it.end) }.filter { it.isAfter(now) }.distinct().sorted().take(2)
            .map { at -> Reminder("widget:${at.toEpochMilli()}", ReminderKind.WIDGET, 0, at, "", "") }

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
