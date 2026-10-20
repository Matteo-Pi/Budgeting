package io.github.matteopi.budgeting.ui.planner

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import io.github.matteopi.budgeting.core.planner.timetable.EntryField
import io.github.matteopi.budgeting.ui.planner.timetable.EntryEditUiState
import io.github.matteopi.budgeting.ui.planner.timetable.EntryForm
import io.github.matteopi.budgeting.ui.planner.timetable.PatternChoice
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The timetable entry editor (docs/PLAN.md §13.9), on Tue 20 Oct 2026 in the autumn term. */
class EntryFormTest {

    private val today = LocalDate.of(2026, 10, 20)
    private val zone = ZoneId.of("Europe/London")
    private val autumn = TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2026, 9, 28), endDate = LocalDate.of(2026, 12, 11))
    private val spring = TermPeriod(id = 2, name = "Spring", startDate = LocalDate.of(2027, 1, 11), endDate = LocalDate.of(2027, 3, 19))
    private val terms = listOf(autumn, spring)
    private val lecture = EntryForm(title = "EC101 Lecture", weekday = DayOfWeek.MONDAY)

    @Test
    fun `a title and different times are needed`() {
        assertEquals(setOf(EntryField.TITLE), EntryForm().errors.keys)
        assertEquals(setOf(EntryField.TIME), lecture.copy(end = LocalTime.of(9, 0)).errors.keys)
        assertEquals(setOf(EntryField.TIME), lecture.copy(start = null).errors.keys)
        assertTrue(lecture.errors.isEmpty())
        assertTrue(lecture.copy(start = LocalTime.of(22, 0), end = LocalTime.of(1, 0)).errors.isEmpty(), "past midnight is fine")
    }

    @Test
    fun `chosen weeks are read from the text`() {
        val chosen = lecture.copy(pattern = PatternChoice.CHOSEN_WEEKS, weeksText = "1-3, 5")
        assertEquals(WeekPattern.TeachingWeeks(setOf(1, 2, 3, 5)), chosen.toEntry().pattern)
        assertTrue(chosen.errors.isEmpty())
        assertTrue(EntryField.WEEKS in chosen.copy(weeksText = "1-x").errors)
        assertTrue(EntryField.WEEKS in chosen.copy(weeksText = "").errors)
    }

    @Test
    fun `a once-only entry takes its weekday from its date and has no term`() {
        val once = lecture.copy(pattern = PatternChoice.ONCE, onceDate = LocalDate.of(2026, 10, 23), termId = 1, inHolidays = true)
        val entry = once.toEntry()
        assertEquals(WeekPattern.Once(LocalDate.of(2026, 10, 23)), entry.pattern)
        assertEquals(DayOfWeek.FRIDAY, entry.weekday)
        assertNull(entry.termId)
        assertFalse(entry.inHolidays)
        assertEquals(setOf(EntryField.DATE), once.copy(onceDate = null).errors.keys)
        assertNull(once.preview(terms, today))
    }

    @Test
    fun `only weekly entries can run in the holidays`() {
        assertTrue(lecture.copy(inHolidays = true).toEntry().inHolidays)
        assertFalse(lecture.copy(inHolidays = true, pattern = PatternChoice.ODD_WEEKS).toEntry().inHolidays)
    }

    @Test
    fun `the preview lists this term's dates`() {
        val chosen = lecture.copy(pattern = PatternChoice.CHOSEN_WEEKS, weeksText = "1-3")
        assertEquals("3 dates in Autumn: 28 Sep, 5 Oct, 12 Oct", chosen.preview(terms, today))
        assertEquals("11 dates in Autumn: 28 Sep, 5 Oct, 12 Oct, 19 Oct, 26 Oct, 2 Nov, …", lecture.preview(terms, today))
        assertEquals("1 date in Spring: 11 Jan 2027", chosen.copy(weeksText = "1", termId = 2).preview(terms, today))
        assertEquals("No dates in Autumn.", chosen.copy(weeksText = "20").preview(terms, today))
    }

    @Test
    fun `without term dates a weekly entry is every week and the others can't be placed`() {
        assertEquals("Every Monday", lecture.preview(emptyList(), today))
        assertNull(lecture.copy(pattern = PatternChoice.ODD_WEEKS).preview(emptyList(), today))
        val state = EntryEditUiState(form = lecture.copy(pattern = PatternChoice.ODD_WEEKS), today = today).derived(zone)
        assertTrue(state.needsTermDates)
    }

    @Test
    fun `an entry survives a round trip through the form`() {
        val entry = TimetableEntry(id = 4, title = "Lab", kind = EntryKind.LAB, moduleId = 2, location = "Campus", room = "L1",
            contact = "Dr Lee", colorArgb = 0xFF2E8B57, note = "Bring goggles", weekday = DayOfWeek.THURSDAY,
            start = LocalTime.of(14, 0), end = LocalTime.of(16, 0), pattern = WeekPattern.TeachingWeeks(setOf(2, 4, 6)), termId = 1)
        val form = EntryForm.from(entry)
        assertEquals(PatternChoice.CHOSEN_WEEKS, form.pattern)
        assertEquals("2, 4, 6", form.weeksText)
        assertEquals(entry, form.toEntry())
    }

    @Test
    fun `the editor warns about clashes with the rest of the timetable`() {
        val seminar = TimetableEntry(id = 7, title = "EC102 Seminar", weekday = DayOfWeek.MONDAY, start = LocalTime.of(9, 30),
            end = LocalTime.of(10, 30))
        val state = EntryEditUiState(form = lecture, terms = terms, others = listOf(seminar), today = today).derived(zone)
        assertEquals(listOf("Overlaps EC102 Seminar on Mon 26 Oct and 6 more dates"), state.clashes, "the term ends on 11 Dec")
        val apart = state.copy(form = lecture.copy(weekday = DayOfWeek.TUESDAY)).derived(zone)
        assertEquals(emptyList(), apart.clashes)
        val untitled = state.copy(form = lecture.copy(title = "")).derived(zone)
        assertEquals(emptyList(), untitled.clashes, "no warnings while the form has errors")
        assertEquals(setOf(EntryField.TITLE), untitled.errors.keys)
    }
}
