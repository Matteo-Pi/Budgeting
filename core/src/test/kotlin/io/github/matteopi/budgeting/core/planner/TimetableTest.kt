package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import io.github.matteopi.budgeting.core.planner.timetable.ClashKind
import io.github.matteopi.budgeting.core.planner.timetable.Clashes
import io.github.matteopi.budgeting.core.planner.timetable.EntryField
import io.github.matteopi.budgeting.core.planner.timetable.EntryRules
import io.github.matteopi.budgeting.core.planner.timetable.Lanes
import io.github.matteopi.budgeting.core.planner.timetable.TeachingWeeks
import io.github.matteopi.budgeting.core.planner.timetable.TermCopy
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.core.planner.timetable.TravelTimes
import io.github.matteopi.budgeting.core.planner.timetable.WeekList
import io.github.matteopi.budgeting.core.planner.timetable.WeekListResult
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The timetable (docs/PLAN.md §13.4): teaching weeks, week lists, occurrences, clashes and lanes. */
class TimetableTest {

    private val zone = ZoneId.of("Europe/London")
    private val autumn = TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2026, 9, 28), endDate = LocalDate.of(2026, 12, 11))
    private val spring = TermPeriod(id = 2, name = "Spring", startDate = LocalDate.of(2027, 1, 11), endDate = LocalDate.of(2027, 3, 26))
    private val terms = listOf(autumn, spring)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private val lecture = TimetableEntry(
        id = 1, title = "EC101 Lecture", kind = EntryKind.LECTURE, location = "Campus", room = "LT1",
        weekday = DayOfWeek.MONDAY, start = LocalTime.of(10, 0), end = LocalTime.of(11, 0),
        pattern = WeekPattern.TeachingWeeks((1..5).toSet() + (7..11).toSet()),
    )

    private fun occurrences(vararg entries: TimetableEntry, exceptions: List<EntryException> = emptyList(), from: LocalDate = autumn.startDate, to: LocalDate = autumn.endDate) =
        Timetable.occurrences(entries.toList(), exceptions, terms, from, to, zone)

    @Test
    fun `teaching weeks count from the week the term starts in`() {
        assertEquals(1, TeachingWeeks.number(d(2026, 9, 28), autumn))
        assertEquals(1, TeachingWeeks.number(d(2026, 10, 4), autumn))
        assertEquals(6, TeachingWeeks.number(d(2026, 11, 2), autumn))
        assertEquals(11, TeachingWeeks.number(d(2026, 12, 7), autumn))
        assertEquals(11, TeachingWeeks.count(autumn))
        val midweek = TermPeriod(id = 3, name = "x", startDate = d(2026, 9, 30), endDate = d(2026, 10, 9))
        assertEquals(1, TeachingWeeks.number(d(2026, 9, 28), midweek), "a term starting on Wednesday: its Monday is week 1")
        assertEquals(2, TeachingWeeks.number(d(2026, 10, 5), midweek))
    }

    @Test
    fun `the checkpoints`() {
        val dates = occurrences(lecture).map { it.date }
        assertEquals(
            listOf(d(2026, 9, 28), d(2026, 10, 5), d(2026, 10, 12), d(2026, 10, 19), d(2026, 10, 26),
                d(2026, 11, 9), d(2026, 11, 16), d(2026, 11, 23), d(2026, 11, 30), d(2026, 12, 7)),
            dates,
        )
        assertFalse(d(2026, 11, 2) in dates, "reading week")
        assertEquals(6, occurrences(lecture.copy(pattern = WeekPattern.OddWeeks)).size)
        assertEquals(5, occurrences(lecture.copy(pattern = WeekPattern.EvenWeeks)).size)
        assertEquals(11, occurrences(lecture.copy(pattern = WeekPattern.EveryWeek)).size)
    }

    @Test
    fun `local times hold across the clock change`() {
        val week5 = occurrences(lecture).first { it.date == d(2026, 10, 19) }
        val week5b = occurrences(lecture).first { it.date == d(2026, 10, 26) }
        assertEquals(Instant.parse("2026-10-19T09:00:00Z"), week5.start, "10:00 BST")
        assertEquals(Instant.parse("2026-10-26T10:00:00Z"), week5b.start, "10:00 GMT after the clocks go back")
        assertEquals(LocalTime.of(10, 0), week5b.startTime)
    }

    @Test
    fun `terms, holidays and once-only entries`() {
        val autumnOnly = lecture.copy(pattern = WeekPattern.EveryWeek, termId = 1)
        val all = occurrences(autumnOnly, from = d(2026, 9, 1), to = d(2027, 3, 31))
        assertEquals(11, all.size, "only in its own term")
        val everyTerm = lecture.copy(pattern = WeekPattern.EveryWeek)
        assertEquals(11 + 11, occurrences(everyTerm, from = d(2026, 9, 1), to = d(2027, 3, 31)).size, "both terms, not the holidays")
        val sport = lecture.copy(id = 2, title = "Football", kind = EntryKind.SPORT, pattern = WeekPattern.EveryWeek, inHolidays = true)
        val christmas = occurrences(sport, from = d(2026, 12, 12), to = d(2027, 1, 10)).map { it.date }
        assertEquals(listOf(d(2026, 12, 14), d(2026, 12, 21), d(2026, 12, 28), d(2027, 1, 4)), christmas, "kept going in the holidays")
        val oddInHolidays = sport.copy(pattern = WeekPattern.OddWeeks)
        assertTrue(occurrences(oddInHolidays, from = d(2026, 12, 12), to = d(2027, 1, 10)).isEmpty(), "week numbers stop outside terms")
        val visit = lecture.copy(id = 3, title = "Open day", pattern = WeekPattern.Once(d(2026, 12, 19)), weekday = DayOfWeek.MONDAY)
        assertEquals(listOf(d(2026, 12, 19)), occurrences(visit, from = d(2026, 12, 1), to = d(2026, 12, 31)).map { it.date }, "any day, any regime")
        assertTrue(occurrences(lecture.copy(archived = true)).isEmpty())
    }

    @Test
    fun `without term dates only every-week entries are placed`() {
        val every = Timetable.occurrences(listOf(lecture.copy(pattern = WeekPattern.EveryWeek)), emptyList(), emptyList(), d(2026, 10, 1), d(2026, 10, 31), zone)
        assertEquals(4, every.size)
        assertTrue(Timetable.occurrences(listOf(lecture), emptyList(), emptyList(), d(2026, 10, 1), d(2026, 10, 31), zone).isEmpty())
        assertTrue(EntryRules.needsTermDates(lecture, emptyList()))
        assertFalse(EntryRules.needsTermDates(lecture, terms))
        assertFalse(EntryRules.needsTermDates(lecture.copy(pattern = WeekPattern.EveryWeek), emptyList()))
    }

    @Test
    fun `exceptions cancel one date or move it`() {
        val exceptions = listOf(
            EntryException(entryId = 1, date = d(2026, 10, 5), cancelled = true),
            EntryException(entryId = 1, date = d(2026, 10, 12), start = LocalTime.of(14, 0), end = LocalTime.of(15, 0), room = "LT2"),
        )
        val all = occurrences(lecture, exceptions = exceptions)
        assertEquals(9, all.size)
        val moved = all.first { it.date == d(2026, 10, 12) }
        assertEquals(LocalTime.of(14, 0), moved.startTime)
        assertEquals("LT2", moved.room)
        assertTrue(moved.changed)
        assertEquals("entry:1:2026-10-12", moved.key)
        val withCancelled = Timetable.occurrences(listOf(lecture), exceptions, terms, autumn.startDate, autumn.endDate, zone, includeCancelled = true)
        assertEquals(10, withCancelled.size)
        assertTrue(withCancelled.single { it.date == d(2026, 10, 5) }.cancelled)
        assertFalse(withCancelled.single { it.date == d(2026, 10, 5) }.changed)
    }

    @Test
    fun `a class past midnight ends the next day`() {
        val late = lecture.copy(pattern = WeekPattern.EveryWeek, start = LocalTime.of(23, 0), end = LocalTime.of(1, 0))
        val o = occurrences(late, from = d(2026, 9, 28), to = d(2026, 9, 28)).single()
        assertEquals(ZonedDateTime.of(2026, 9, 29, 1, 0, 0, 0, zone).toInstant(), o.end)
    }

    @Test
    fun `week lists`() {
        assertEquals(WeekListResult.Ok((1..5).toSet() + (7..11).toSet()), WeekList.parse("1-5, 7–11"))
        assertEquals(WeekListResult.Ok(setOf(1, 3, 5)), WeekList.parse("1 3 5"))
        assertEquals(WeekListResult.Ok((2..6).toSet()), WeekList.parse("2 to 6"))
        assertEquals(WeekListResult.Ok(setOf(4)), WeekList.parse(" 4 "))
        for (bad in listOf("", " , ", "5-1", "0", "61", "abc", "1-x")) assertIs<WeekListResult.Error>(WeekList.parse(bad), bad)
        assertEquals("1–5, 7–11", WeekList.format((1..5).toSet() + (7..11).toSet()))
        assertEquals("1, 3, 5", WeekList.format(setOf(5, 1, 3)))
        assertEquals("1, 2", WeekList.format(setOf(1, 2)))
        assertEquals("", WeekList.format(emptySet()))
        val random = Random(6)
        repeat(500) {
            val weeks = (1..random.nextInt(1, 20)).map { random.nextInt(1, 61) }.toSet()
            assertEquals(WeekListResult.Ok(weeks), WeekList.parse(WeekList.format(weeks)), "$weeks")
        }
    }

    private fun occ(id: Long, startH: Int, startM: Int, endH: Int, endM: Int, location: String) = Occurrence(
        entryId = id, date = d(2026, 10, 19),
        start = ZonedDateTime.of(2026, 10, 19, startH, startM, 0, 0, zone).toInstant(),
        end = ZonedDateTime.of(2026, 10, 19, endH, endM, 0, 0, zone).toInstant(),
        startTime = LocalTime.of(startH, startM), endTime = LocalTime.of(endH, endM),
        title = "C$id", kind = EntryKind.LECTURE, moduleId = null, location = location, room = "", colorArgb = null,
    )

    @Test
    fun `clashes and tight changes of place`() {
        val a = occ(1, 9, 0, 10, 0, "Campus")
        val b = occ(2, 9, 30, 10, 30, "Campus")
        val c = occ(3, 10, 40, 11, 0, "Hospital site")
        val d = occ(4, 11, 0, 12, 0, "Hospital site")
        val clashes = Clashes.find(listOf(d, c, b, a))
        assertEquals(listOf(ClashKind.OVERLAP to (1L to 2L), ClashKind.TIGHT to (2L to 3L)), clashes.map { it.kind to (it.first.entryId to it.second.entryId) })
        assertEquals(10, clashes[1].gapMinutes)
        assertTrue(Clashes.find(listOf(b, c), TravelTimes(defaultMinutes = 10)).isEmpty(), "10 minutes is enough when travel takes 10")
        assertTrue(Clashes.find(listOf(occ(5, 9, 0, 10, 0, ""), occ(6, 10, 0, 11, 0, "Town"))).isEmpty(), "no place, no travel")
    }

    @Test
    fun `travel times`() {
        val travel = TravelTimes(defaultMinutes = 15, fromHome = mapOf("Campus" to 25))
        assertEquals(25, travel.fromHome(" campus "))
        assertEquals(15, travel.fromHome("Town"))
        assertEquals(0, travel.fromHome(""))
        assertEquals(0, travel.between("Campus", "CAMPUS"))
        assertEquals(15, travel.between("Campus", "Town"))
    }

    @Test
    fun `lanes put overlapping items side by side`() {
        data class Item(val name: String, val start: Long, val end: Long)
        val items = listOf(Item("a", 0, 60), Item("b", 30, 90), Item("c", 60, 120), Item("d", 200, 260), Item("e", 0, 30))
        val placed = Lanes.assign(items, { it.start }, { it.end }).associate { it.item.name to (it.lane to it.lanes) }
        assertEquals(0 to 2, placed["e"], "e starts with a and ends first, so it is placed first")
        assertEquals(1 to 2, placed["a"])
        assertEquals(0 to 2, placed["b"], "b starts as e ends, so it takes e's lane")
        assertEquals(1 to 2, placed["c"], "c starts as a ends, so it takes a's lane")
        assertEquals(0 to 1, placed["d"], "a new cluster")
        val random = Random(42)
        repeat(200) {
            val list = (1..random.nextInt(1, 12)).map { val s = random.nextLong(0, 600); Item("$it", s, s + random.nextLong(15, 180)) }
            val result = Lanes.assign(list, { it.start }, { it.end })
            assertEquals(list.size, result.size)
            for (x in result) for (y in result) {
                if (x !== y && x.lane == y.lane) assertTrue(x.item.end <= y.item.start || y.item.end <= x.item.start, "same lane never overlaps")
            }
            assertTrue(result.all { it.lane < it.lanes })
        }
    }

    @Test
    fun `entry rules and copying a term`() {
        assertEquals(setOf(EntryField.TITLE), EntryRules.errors(lecture.copy(title = " ")).keys)
        assertEquals(setOf(EntryField.TIME), EntryRules.errors(lecture.copy(end = lecture.start)).keys)
        assertEquals(setOf(EntryField.WEEKS), EntryRules.errors(lecture.copy(pattern = WeekPattern.TeachingWeeks(emptySet()))).keys)
        assertTrue(EntryRules.errors(lecture).isEmpty())
        val mine = listOf(lecture.copy(termId = 1), lecture.copy(id = 9, termId = null), lecture.copy(id = 10, termId = 1, archived = true))
        val copied = TermCopy.rows(mine, autumn, spring)
        assertEquals(1, copied.size)
        assertEquals(0L, copied.single().id)
        assertEquals(2L, copied.single().termId)
        assertEquals(lecture.pattern, copied.single().pattern)
    }

    @Test
    fun `dates for the editor's preview`() {
        assertEquals(10, Timetable.dates(lecture, terms, autumn.startDate, autumn.endDate).size)
    }
}
