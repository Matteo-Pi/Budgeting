package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.core.planner.reminders.ReminderPlan
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Planner's wording (docs/PLAN.md §13.9) and task reminders (§13.4), in Europe/London on Tue 20 Oct 2026. */
class PlannerTextAndRemindersTest {

    private val today = LocalDate.of(2026, 10, 20)
    private val zone = ZoneId.of("Europe/London")
    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant()

    @Test
    fun `durations, times and ranges`() {
        assertEquals("0 m", PlannerText.duration(0))
        assertEquals("45 m", PlannerText.duration(45))
        assertEquals("2 h", PlannerText.duration(120))
        assertEquals("3 h 20 m", PlannerText.duration(200))
        assertEquals("0 m", PlannerText.duration(-5))
        assertEquals("09:05", PlannerText.time(LocalTime.of(9, 5)))
        assertEquals("13:00–14:30", PlannerText.range(LocalTime.of(13, 0), LocalTime.of(14, 30)))
    }

    @Test
    fun `days and dates`() {
        assertEquals("Today", PlannerText.day(today, today))
        assertEquals("Tomorrow", PlannerText.day(today.plusDays(1), today))
        assertEquals("Yesterday", PlannerText.day(today.minusDays(1), today))
        assertEquals("Friday", PlannerText.day(LocalDate.of(2026, 10, 23), today))
        assertEquals("Monday", PlannerText.day(LocalDate.of(2026, 10, 26), today))
        assertEquals("Tue 27 Oct", PlannerText.day(LocalDate.of(2026, 10, 27), today))
        assertEquals("Sun 18 Oct", PlannerText.day(LocalDate.of(2026, 10, 18), today))
        assertEquals("Tue 1 Sep", PlannerText.date(LocalDate.of(2026, 9, 1), today), "September is Sep, not Sept")
        assertEquals("Fri 1 Jan 2027", PlannerText.date(LocalDate.of(2027, 1, 1), today))
    }

    @Test
    fun `countdowns and due texts`() {
        assertEquals("today", PlannerText.countdown(today, today))
        assertEquals("tomorrow", PlannerText.countdown(today.plusDays(1), today))
        assertEquals("in 3 days", PlannerText.countdown(today.plusDays(3), today))
        assertEquals("yesterday", PlannerText.countdown(today.minusDays(1), today))
        assertEquals("4 days ago", PlannerText.countdown(today.minusDays(4), today))
        assertEquals("Due today at 15:00", PlannerText.due(today, LocalTime.of(15, 0), today))
        assertEquals("Due tomorrow", PlannerText.due(today.plusDays(1), null, today))
        assertEquals("Due Friday", PlannerText.due(LocalDate.of(2026, 10, 23), null, today))
        assertEquals("Due Fri 6 Nov", PlannerText.due(LocalDate.of(2026, 11, 6), null, today))
        assertEquals("Overdue since yesterday", PlannerText.due(today.minusDays(1), null, today))
        assertEquals("Overdue by 2 days", PlannerText.due(today.minusDays(2), LocalTime.NOON, today))
    }

    @Test
    fun `typed times`() {
        val cases = mapOf(
            "15:30" to LocalTime.of(15, 30), "9:05" to LocalTime.of(9, 5), "1530" to LocalTime.of(15, 30),
            "930" to LocalTime.of(9, 30), "9" to LocalTime.of(9, 0), "0" to LocalTime.MIDNIGHT, "23:59" to LocalTime.of(23, 59),
            "3pm" to LocalTime.of(15, 0), "3:30 pm" to LocalTime.of(15, 30), "12am" to LocalTime.MIDNIGHT, "12pm" to LocalTime.NOON,
            " 08.15 " to LocalTime.of(8, 15),
        )
        cases.forEach { (text, time) -> assertEquals(time, PlannerText.parseTime(text), text) }
        for (bad in listOf("", "24:00", "12:60", "13pm", "0am", "abc", "9:5", "12345", "-1")) assertNull(PlannerText.parseTime(bad), bad)
    }

    @Test
    fun `typed durations`() {
        val cases = mapOf(
            "90" to 90, "45m" to 45, "45 min" to 45, "2h" to 120, "1.5h" to 90, "1h30" to 90, "1h 30m" to 90, "1:30" to 90,
            "2 hrs" to 120, "10 minutes" to 10,
        )
        cases.forEach { (text, minutes) -> assertEquals(minutes, PlannerText.parseDuration(text), text) }
        for (bad in listOf("", "0", "0h", "h", "abc", "1:3", "-5")) assertNull(PlannerText.parseDuration(bad), bad)
    }

    @Test
    fun `repeats and reminders in words`() {
        assertEquals("Every day", PlannerText.repeat(TaskRepeat(RepeatKind.DAYS)))
        assertEquals("Every 3 days", PlannerText.repeat(TaskRepeat(RepeatKind.DAYS, 3)))
        assertEquals("Every week", PlannerText.repeat(TaskRepeat(RepeatKind.WEEKS)))
        assertEquals("Every 2 weeks", PlannerText.repeat(TaskRepeat(RepeatKind.WEEKS, 2)))
        assertEquals("Mon, Wed, Fri", PlannerText.repeat(TaskRepeat(RepeatKind.WEEKDAYS, weekdays = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))))
        assertEquals("At the time", PlannerText.reminder(0, timed = true))
        assertEquals("On the day", PlannerText.reminder(0, timed = false))
        assertEquals("15 minutes before", PlannerText.reminder(15, timed = true))
        assertEquals("1 hour before", PlannerText.reminder(60, timed = true))
        assertEquals("2 hours before", PlannerText.reminder(120, timed = true))
        assertEquals("1 day before", PlannerText.reminder(1440, timed = false))
    }

    private val submit = Task(id = 1, title = "Submit form", dueDate = LocalDate.of(2026, 10, 23), dueTime = LocalTime.of(17, 0), reminderMinutesBefore = 60)
    private val untimed = Task(id = 2, title = "Buy stamps", dueDate = LocalDate.of(2026, 10, 23), reminderMinutesBefore = 0)
    private val dayBefore = Task(id = 3, title = "Revise", dueDate = LocalDate.of(2026, 10, 23), reminderMinutesBefore = 1440)

    private fun reminders(vararg tasks: Task) = ReminderPlan.taskReminders(tasks.toList(), ReminderPlan.DEFAULT_TASK_TIME, zone, today)

    @Test
    fun `the checkpoints`() {
        val all = reminders(submit, untimed, dayBefore)
        assertEquals(at(10, 23, 16), all.first { it.itemId == 1L }.at, "an hour before 17:00")
        assertEquals(at(10, 23, 9), all.first { it.itemId == 2L }.at, "an untimed task: 09:00 on the day")
        assertEquals(at(10, 22, 9), all.first { it.itemId == 3L }.at, "a day before 09:00")
        assertEquals("Due Friday at 17:00", all.first().text)
    }

    @Test
    fun `no reminder without a date, a reminder, or once done`() {
        assertTrue(reminders(submit.copy(dueDate = null), submit.copy(reminderMinutesBefore = null), submit.copy(completedAt = Instant.EPOCH)).isEmpty())
    }

    @Test
    fun `the key changes when what it reminds of changes`() {
        val key = reminders(submit).single().key
        assertEquals(key, reminders(submit.copy(title = "Renamed")).single().key)
        assertTrue(key != reminders(submit.copy(dueDate = LocalDate.of(2026, 10, 24))).single().key)
        assertTrue(key != reminders(submit.copy(reminderMinutesBefore = 15)).single().key)
        assertTrue(key != reminders(submit.copy(dueTime = LocalTime.of(18, 0))).single().key)
    }

    @Test
    fun `next is the earliest coming one`() {
        val all = reminders(submit, untimed, dayBefore)
        assertEquals(3L, ReminderPlan.next(all, at(10, 21, 12))?.itemId)
        assertEquals(1L, ReminderPlan.next(all, at(10, 23, 9))?.itemId, "one exactly now is not next")
        assertNull(ReminderPlan.next(all, at(10, 23, 16)))
    }

    @Test
    fun `due between the last post and now, once each, and not too late`() {
        val all = reminders(submit, untimed, dayBefore)
        assertEquals(listOf(2L, 1L), ReminderPlan.dueBetween(all, lastFired = at(10, 22, 9), now = at(10, 23, 16)).map { it.itemId })
        assertEquals(listOf(1L), ReminderPlan.dueBetween(all, lastFired = at(10, 23, 9), now = at(10, 23, 16)).map { it.itemId })
        assertTrue(ReminderPlan.dueBetween(all, lastFired = at(10, 23, 16), now = at(10, 23, 16)).isEmpty(), "posted already")
        // The phone was off for a day: 13 h late is dropped, 12 h is still posted.
        assertEquals(listOf(1L), ReminderPlan.dueBetween(all, lastFired = null, now = at(10, 24, 4)).map { it.itemId })
        assertTrue(ReminderPlan.dueBetween(all, lastFired = null, now = at(10, 24, 5)).isEmpty())
    }

    @Test
    fun `reminders keep the local time across the clock change`() {
        val afterChange = Task(id = 4, title = "Seminar prep", dueDate = LocalDate.of(2026, 10, 26), dueTime = LocalTime.of(9, 0), reminderMinutesBefore = 30)
        assertEquals(ZonedDateTime.of(2026, 10, 26, 8, 30, 0, 0, zone).toInstant(), reminders(afterChange).single().at)
        assertEquals(Instant.parse("2026-10-26T08:30:00Z"), reminders(afterChange).single().at, "GMT again after 25 Oct")
    }
}
