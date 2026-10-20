package io.github.matteopi.budgeting.ui.planner

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.ui.planner.timetable.ItemKind
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableMapping
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The timetable's grid, day list and month (docs/PLAN.md §13.9), in the autumn term of 2026. */
class TimetableMappingTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 20) // a Tuesday, in teaching week 4
    private val monday = LocalDate.of(2026, 10, 19)
    private val autumn = TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2026, 9, 28), endDate = LocalDate.of(2026, 12, 11))
    private val terms = listOf(autumn)
    private val ec101 = Module(id = 1, code = "EC101", name = "Economics", colorArgb = 0xFF3F7CAC)
    private val modules = listOf(ec101)

    private val lecture = TimetableEntry(id = 1, title = "EC101 Lecture", moduleId = 1, room = "2.14", location = "Campus",
        weekday = DayOfWeek.MONDAY, start = LocalTime.of(9, 0), end = LocalTime.of(11, 0))
    private val seminar = TimetableEntry(id = 2, title = "EC102 Seminar", kind = EntryKind.SEMINAR, location = "Campus",
        weekday = DayOfWeek.MONDAY, start = LocalTime.of(10, 0), end = LocalTime.of(11, 0), colorArgb = 0xFF2E8B57)
    private val gym = TimetableEntry(id = 3, title = "Gym", kind = EntryKind.SPORT, weekday = DayOfWeek.TUESDAY,
        start = LocalTime.of(7, 0), end = LocalTime.of(8, 0), inHolidays = true)

    private fun occurrences(from: LocalDate, to: LocalDate, exceptions: List<EntryException> = emptyList()) =
        Timetable.occurrences(listOf(lecture, seminar, gym), exceptions, terms, from, to, zone, includeCancelled = true)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant()

    @Test
    fun `the term label counts teaching weeks`() {
        assertEquals("Week 4 · Autumn", TimetableMapping.termLabel(monday, terms))
        assertEquals("Week 1 · Autumn", TimetableMapping.termLabel(LocalDate.of(2026, 9, 28), terms))
        assertEquals("Holidays", TimetableMapping.termLabel(LocalDate.of(2026, 12, 21), terms))
        assertEquals("", TimetableMapping.termLabel(monday, emptyList()))
    }

    @Test
    fun `overlapping classes share the width, each in its own lane`() {
        val items = TimetableMapping.dayItems(monday, occurrences(monday, monday), emptyList(), emptyList(), modules, zone)
        assertEquals(listOf("EC101 Lecture", "EC102 Seminar"), items.map { it.title })
        assertEquals(listOf(0 to 2, 1 to 2), items.map { it.lane to it.lanes })
        assertEquals(9 * 60, items[0].startMinute)
        assertEquals(11 * 60, items[0].endMinute)
        assertEquals(0xFF3F7CAC, items[0].colorArgb, "the module's colour")
        assertEquals(0xFF2E8B57, items[1].colorArgb, "the entry's own colour")
        assertEquals("Lecture · 2.14 · Campus", items[0].detail)
        assertEquals("entry:1:2026-10-19", items[0].key)
    }

    @Test
    fun `a cancelled or changed class says so`() {
        val exceptions = listOf(
            EntryException(entryId = 1, date = monday, cancelled = true),
            EntryException(entryId = 2, date = monday, room = "B12"),
        )
        val items = TimetableMapping.dayItems(monday, occurrences(monday, monday, exceptions), emptyList(), emptyList(), modules, zone)
        val byEntry = items.associateBy { it.entryId }
        assertEquals(ItemKind.CANCELLED, byEntry.getValue(1).kind)
        assertTrue(byEntry.getValue(1).detail.endsWith("Cancelled this week"))
        assertEquals(ItemKind.CLASS, byEntry.getValue(2).kind)
        assertTrue(byEntry.getValue(2).changed)
        assertEquals("B12", byEntry.getValue(2).room)
        assertTrue(byEntry.getValue(2).detail.endsWith("Changed this week"))
    }

    @Test
    fun `calendar events and timed deadlines join the classes`() {
        val event = CalendarInstance(eventId = 7, calendarId = 1, title = "Dentist", location = "High St", start = at(monday, 13),
            end = at(monday, 13, 30), allDay = false, busy = true, colorArgb = null)
        val untitled = event.copy(eventId = 8, title = "", start = at(monday, 18), end = at(monday, 18))
        val exam = Task(id = 4, title = "EC101 midterm", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.EXAM,
            dueDate = monday, dueTime = LocalTime.of(14, 0), lengthMinutes = 90, moduleId = 1)
        val essay = Task(id = 5, title = "Essay", kind = TaskKind.DEADLINE, dueDate = monday, dueTime = LocalTime.of(17, 0), moduleId = 1)
        val untimed = Task(id = 6, title = "Reading", kind = TaskKind.DEADLINE, dueDate = monday)
        val items = TimetableMapping.dayItems(monday, emptyList(), listOf(event, untitled), listOf(exam, essay, untimed), modules, zone)
        assertEquals(listOf("Dentist", "EC101 midterm", "Essay", "(No title)"), items.map { it.title })
        assertEquals(listOf(ItemKind.EVENT, ItemKind.EXAM, ItemKind.TASK, ItemKind.EVENT), items.map { it.kind })
        assertEquals(14 * 60 + 90, items[1].endMinute, "an exam lasts its length")
        assertEquals(17 * 60 + 30, items[2].endMinute, "a deadline without a length takes 30 minutes")
        assertEquals(18 * 60 + 15, items[3].endMinute, "a zero-length event still shows")
        assertEquals(0xFF3F7CAC, items[2].colorArgb)
    }

    @Test
    fun `a class past midnight is drawn to the end of the day`() {
        val late = gym.copy(id = 9, weekday = DayOfWeek.MONDAY, start = LocalTime.of(22, 0), end = LocalTime.of(1, 0))
        val occ = Timetable.occurrences(listOf(late), emptyList(), terms, monday, monday, zone)
        val item = TimetableMapping.dayItems(monday, occ, emptyList(), emptyList(), modules, zone).single()
        assertEquals(24 * 60, item.endMinute)
    }

    @Test
    fun `the week widens its hours to fit and lists the clashes`() {
        val week = TimetableMapping.week(monday, today, occurrences(monday, monday.plusDays(6)), emptyList(), emptyList(), modules, terms, zone)
        assertEquals("19–25 Oct", week.title)
        assertEquals("Week 4 · Autumn", week.termLabel)
        assertEquals(7, week.firstHour, "the 07:00 gym")
        assertEquals(TimetableMapping.DEFAULT_LAST_HOUR, week.lastHour)
        assertEquals(listOf("Mon 19", "Tue 20"), week.days.take(2).map { it.label })
        assertTrue(week.days[1].isToday)
        assertEquals(listOf("Mon: EC101 Lecture and EC102 Seminar overlap"), week.clashes)
    }

    @Test
    fun `week titles across months and years`() {
        assertEquals("28 Sep – 4 Oct", TimetableMapping.weekTitle(LocalDate.of(2026, 9, 28)))
        assertEquals("28 Dec 2026 – 3 Jan 2027", TimetableMapping.weekTitle(LocalDate.of(2026, 12, 28)))
    }

    @Test
    fun `all-day events cover their days, the end day not included`() {
        val trip = CalendarInstance(eventId = 1, calendarId = 1, title = "Field trip", location = "",
            start = monday.atStartOfDay(zone).toInstant(), end = monday.plusDays(2).atStartOfDay(zone).toInstant(),
            allDay = true, busy = true, colorArgb = null)
        assertEquals(listOf("Field trip"), TimetableMapping.allDay(monday, listOf(trip), zone))
        assertEquals(listOf("Field trip"), TimetableMapping.allDay(today, listOf(trip), zone))
        assertEquals(emptyList(), TimetableMapping.allDay(monday.plusDays(2), listOf(trip), zone))
        val week = TimetableMapping.week(monday, today, emptyList(), listOf(trip), emptyList(), modules, terms, zone)
        assertEquals(emptyList(), week.days[0].items, "not drawn in the grid")
    }

    @Test
    fun `the month has six weeks from a Monday, counting what is on each day`() {
        val from = LocalDate.of(2026, 9, 28)
        val days = TimetableMapping.month(today, today, occurrences(from, from.plusDays(41)), emptyList(), emptyList(), modules, zone)
        assertEquals(42, days.size)
        assertEquals(from, days.first().date)
        assertTrue(!days.first().inMonth && days[3].inMonth, "1 Oct is the Thursday")
        val mon = days.first { it.date == monday }
        assertEquals(2, mon.count)
        assertEquals(listOf(0xFF3F7CAC, 0xFF2E8B57), mon.colours)
        assertTrue(days.first { it.date == today }.isToday)
    }
}
