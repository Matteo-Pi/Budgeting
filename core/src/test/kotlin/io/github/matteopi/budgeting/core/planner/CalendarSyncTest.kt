package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.calendar.CalendarSync
import io.github.matteopi.budgeting.core.planner.calendar.DesiredEvent
import io.github.matteopi.budgeting.core.planner.calendar.ExistingEvent
import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.PlanOrigin
import io.github.matteopi.budgeting.core.planner.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Writing the Planner into a calendar (docs/PLAN.md §13.4): insert, unchanged, update, delete and re-link by UID. */
class CalendarSyncTest {

    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private val zone = ZoneId.of("Europe/London")
    private fun hours(h: Long) = now.plusSeconds(h * 3600)

    private val study = DesiredEvent("block:1", "Study EC101", hours(2), hours(3))
    private val exam = DesiredEvent("task:5", "MA102 exam", hours(48), hours(50), "Sports hall")

    private fun existing(id: Long, event: DesiredEvent, title: String = event.title) =
        ExistingEvent(id, event.uid, title, event.start, event.end, event.location, event.description)

    private fun link(id: Long, event: DesiredEvent, eventId: Long) = CalendarLink(id, event.key, 7, eventId, event.uid, event.contentHash)

    @Test
    fun `uids are stable and recognisable`() {
        assertEquals("planner-entry-12-2026-10-20@budgeting", CalendarSync.uidOf("entry:12:2026-10-20"))
        assertTrue(CalendarSync.isPlannerUid(study.uid))
        assertFalse(CalendarSync.isPlannerUid("abc@google.com"))
        assertFalse(CalendarSync.isPlannerUid(null))
        assertEquals(study.contentHash, study.copy().contentHash)
        assertTrue(study.contentHash != study.copy(title = "Study MA102").contentHash)
    }

    @Test
    fun `new events are inserted, unchanged ones left alone, changed ones updated`() {
        val first = CalendarSync.diff(listOf(study, exam), emptyList(), emptyList(), now)
        assertEquals(listOf(study, exam), first.inserts)
        assertTrue(first.updates.isEmpty() && first.deletes.isEmpty())

        val links = listOf(link(1, study, 100), link(2, exam, 101))
        val calendar = listOf(existing(100, study), existing(101, exam))
        assertTrue(CalendarSync.diff(listOf(study, exam), links, calendar, now).isEmpty, "nothing changed")

        val moved = exam.copy(start = hours(72), end = hours(74))
        val second = CalendarSync.diff(listOf(study, moved), links, calendar, now)
        assertEquals(listOf(101L to moved), second.updates)
        assertTrue(second.inserts.isEmpty() && second.relinks.isEmpty())
    }

    @Test
    fun `what is no longer wanted is deleted unless it is in the past`() {
        val past = DesiredEvent("block:2", "Study yesterday", hours(-26), hours(-25))
        val links = listOf(link(1, study, 100), link(2, exam, 101), link(3, past, 102))
        val calendar = listOf(existing(100, study), existing(101, exam), existing(102, past))
        val plan = CalendarSync.diff(listOf(study), links, calendar, now)
        assertEquals(listOf(101L), plan.deletes, "the exam went; yesterday's block stays in the calendar")
        assertEquals(listOf(2L, 3L), plan.dropLinks)
    }

    @Test
    fun `a restore or a new phone re-links by UID instead of writing twice`() {
        val calendar = listOf(existing(500, study), existing(501, exam, title = "MA102 exam (old)"))
        val plan = CalendarSync.diff(listOf(study, exam), emptyList(), calendar, now)
        assertTrue(plan.inserts.isEmpty(), "found by UID")
        assertEquals(listOf(study to 500L, exam to 501L), plan.relinks)
        assertEquals(listOf(501L to exam), plan.updates, "and brought up to date")

        // A stale link (event ids differ on the new phone) is dropped and replaced.
        val stale = listOf(link(1, study, 999))
        val again = CalendarSync.diff(listOf(study), stale, listOf(existing(500, study)), now)
        assertEquals(listOf(study to 500L), again.relinks)
        assertEquals(listOf(1L), again.dropLinks)
    }

    @Test
    fun `orphans with a Planner UID in the future are removed, other events never touched`() {
        val orphan = existing(600, DesiredEvent("task:77", "Deleted task", hours(5), hours(6)))
        val mine = ExistingEvent(601, "abc@google.com", "Dentist", hours(5), hours(6), "", "")
        val plan = CalendarSync.diff(emptyList(), emptyList(), listOf(orphan, mine), now)
        assertEquals(listOf(600L), plan.deletes)
    }

    @Test
    fun `tasks with a time become events of their length`() {
        val date = LocalDate.of(2026, 10, 23)
        val tasks = listOf(
            Task(id = 1, title = "Submit form", dueDate = date, dueTime = LocalTime.of(17, 0)),
            Task(id = 2, title = "Exam", dueDate = date, dueTime = LocalTime.of(9, 30), lengthMinutes = 120, location = "Hall"),
            Task(id = 3, title = "No time", dueDate = date),
            Task(id = 4, title = "Done", dueDate = date, dueTime = LocalTime.NOON, completedAt = Instant.EPOCH),
            Task(id = 5, title = "Past", dueDate = LocalDate.of(2026, 10, 19), dueTime = LocalTime.NOON),
        )
        val events = CalendarSync.taskEvents(tasks, now, zone)
        assertEquals(listOf("task:1", "task:2"), events.map { it.key })
        assertEquals(Instant.parse("2026-10-23T16:30:00Z"), events[0].end, "30 minutes from 17:00 BST")
        assertEquals(Instant.parse("2026-10-23T10:30:00Z"), events[1].end)
        assertEquals("Hall", events[1].location)
    }

    @Test
    fun `accepted suggestions in the future are written back, the user's own blocks are not`() {
        val accepted = PlanBlock(3, hours(4), hours(5), categoryId = 1, origin = PlanOrigin.SUGGESTION, note = "Study EC101")
        val habit = PlanBlock(4, hours(6), hours(7), categoryId = 1, origin = PlanOrigin.HABIT)
        val own = PlanBlock(5, hours(8), hours(9), categoryId = 1)
        val past = accepted.copy(id = 6, start = hours(-3), end = hours(-2))
        assertEquals(
            listOf(DesiredEvent("block:3", "Study EC101", hours(4), hours(5)), DesiredEvent("block:4", "Study", hours(6), hours(7))),
            CalendarSync.blockEvents(listOf(accepted, habit, own, past), now),
        )
    }
}
