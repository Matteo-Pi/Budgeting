package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.freetime.BusyItem
import io.github.matteopi.budgeting.core.planner.freetime.FreeSettings
import io.github.matteopi.budgeting.core.planner.freetime.FreeTime
import io.github.matteopi.budgeting.core.planner.freetime.MealSlot
import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import io.github.matteopi.budgeting.core.planner.model.DayReview
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.reminders.ReminderKind
import io.github.matteopi.budgeting.core.planner.reminders.ReminderPlan
import io.github.matteopi.budgeting.core.planner.todo.DeadlineState
import io.github.matteopi.budgeting.core.planner.todo.DeadlineStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Free time (docs/PLAN.md §13.4 checkpoints, Mon 2 Nov 2026), the on-track test and the review reminder. */
class FreeTimeTest {

    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 11, 2)
    private fun t(h: Int, m: Int = 0): Instant = ZonedDateTime.of(date.atTime(h, m), zone).toInstant()
    private fun hm(i: Interval) = "${i.start.atZone(zone).toLocalTime()}-${i.end.atZone(zone).toLocalTime()}"

    private val settings = FreeSettings(meals = listOf(MealSlot(12 * 60 + 30, 13 * 60 + 15)), defaultBufferMinutes = 15,
        travelMinutes = mapOf("Campus" to 25), minGapMinutes = 30)
    private val waking = DayWindows.wakingWindow(date, DaySettings(wakeMinutes = List(7) { 8 * 60 }, sleepMinutes = List(7) { 23 * 60 }), zone)
    private val lectures = listOf(BusyItem(Interval(t(10), t(11)), "Campus"), BusyItem(Interval(t(14), t(16)), "campus "))

    @Test
    fun `the checkpoint day has 625 minutes free`() {
        val day = FreeTime.compute(waking, lectures, FreeTime.meals(date, settings, zone), settings.travel, settings.minGapMinutes)
        assertEquals(listOf("08:00-09:35", "11:00-12:30", "13:15-14:00", "16:25-23:00"), day.free.map(::hm))
        assertEquals(625, day.minutes)
    }

    @Test
    fun `a dentist in town takes travel both ways`() {
        val busy = lectures + BusyItem(Interval(t(17), t(17, 30)), "Town")
        val day = FreeTime.compute(waking, busy, FreeTime.meals(date, settings, zone), settings.travel, settings.minGapMinutes)
        assertEquals(listOf("08:00-09:35", "11:00-12:30", "13:15-14:00", "16:00-16:45", "17:45-23:00"), day.free.map(::hm))
        assertEquals(590, day.minutes)
    }

    @Test
    fun `items without a place and short gaps`() {
        val busy = listOf(BusyItem(Interval(t(9), t(10))), BusyItem(Interval(t(10, 20), t(12))))
        val day = FreeTime.compute(waking, busy, emptyList(), settings.travel, 30)
        assertEquals(listOf("08:00-09:00", "12:00-23:00"), day.free.map(::hm), "no travel without places; the 20-minute gap is too short")
    }

    @Test
    fun `busy items are classes, busy events the app didn't write, and timed exams`() {
        val occ = Occurrence(1, date, t(10), t(11), LocalTime.of(10, 0), LocalTime.of(11, 0), "EC101", EntryKind.LECTURE, null, "Campus", "LT1", null)
        val event = CalendarInstance(1, 1, "Dentist", "Town", t(17), t(17, 30), allDay = false, busy = true, colorArgb = null)
        val exam = Task(id = 4, title = "Midterm", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.EXAM, dueDate = date,
            dueTime = LocalTime.of(14, 0), lengthMinutes = 90, location = "Hall")
        val items = FreeTime.busyItems(
            listOf(occ, occ.copy(entryId = 2, cancelled = true)),
            listOf(event, event.copy(eventId = 2, busy = false), event.copy(eventId = 3, allDay = true), event.copy(eventId = 9)),
            listOf(exam, exam.copy(id = 5, dueTime = null), Task(id = 6, title = "Essay", dueDate = date, dueTime = LocalTime.NOON)),
            zone,
            appEventIds = setOf(9L),
        )
        assertEquals(listOf("10:00-11:00 Campus", "17:00-17:30 Town", "14:00-15:30 Hall"), items.map { "${hm(it.interval)} ${it.location}" })
    }

    @Test
    fun `unplanned free time leaves out plan blocks`() {
        val free = listOf(Interval(t(8), t(10)), Interval(t(13), t(18)))
        val plans = listOf(PlanBlock(start = t(14), end = t(15), categoryId = 1))
        assertEquals(listOf("08:00-10:00", "13:00-14:00", "15:00-18:00"), FreeTime.unplanned(free, plans).map(::hm))
        assertEquals(240, FreeTime.unplannedMinutes(free, plans, Interval(t(9), t(17))), "09:00–10:00, 13:00–14:00 and 15:00–17:00")
    }

    @Test
    fun `a deadline is on track, tight or at risk against the free time`() {
        val essay = Task(id = 1, title = "Essay", kind = TaskKind.DEADLINE, dueDate = date.plusDays(4), estimateMinutes = 6 * 60)
        fun state(free: Int) = DeadlineStatus.of(essay, emptyList(), emptyMap(), date, free).state
        assertEquals(DeadlineState.ON_TRACK, state(12 * 60))
        assertEquals(DeadlineState.TIGHT, state(9 * 60))
        assertEquals(DeadlineState.AT_RISK, state(5 * 60))
    }

    @Test
    fun `the review reminder skips finished days and can be off`() {
        val time = LocalTime.of(21, 30)
        val reminders = ReminderPlan.dayReviewReminders(time, emptyList(), date, zone)
        assertEquals(listOf(t(21, 30), t(21, 30).plusSeconds(86_400)), reminders.map { it.at })
        assertTrue(reminders.all { it.kind == ReminderKind.DAY_REVIEW })
        val finished = listOf(DayReview(date, finishedAt = t(22)), DayReview(date.plusDays(1)))
        assertEquals(listOf(date.plusDays(1).toEpochDay()), ReminderPlan.dayReviewReminders(time, finished, date, zone).map { it.itemId })
        assertTrue(ReminderPlan.dayReviewReminders(null, emptyList(), date, zone).isEmpty())
        assertEquals(t(21, 30), ReminderPlan.next(reminders, t(20))?.at)
    }
}
