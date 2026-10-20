package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.todo.QuickAdd
import io.github.matteopi.budgeting.core.planner.todo.QuickAddPart
import io.github.matteopi.budgeting.core.planner.todo.TaskQuickAdd
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One-line tasks (docs/PLAN.md §13.4), read on Tue 20 Oct 2026. */
class TaskQuickAddTest {

    private val today = LocalDate.of(2026, 10, 20)
    private val modules = listOf(Module(id = 7, code = "EC101", name = "Principles of Economics"), Module(id = 8, code = "MA102"))

    private fun read(text: String) = TaskQuickAdd.parse(text, today, modules)

    private fun date(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)

    @Test
    fun `the checkpoint table`() {
        assertEquals(
            QuickAdd("Econ essay draft", date(10, 23), LocalTime.of(15, 0), 120, moduleId = 7),
            read("Econ essay draft fri 3pm 2h #EC101").copy(spans = emptyList()),
        )
        assertEquals(QuickAdd("Pay rent", date(10, 21), priority = Priority.HIGH), read("Pay rent tomorrow !!!").copy(spans = emptyList()))
        assertEquals(QuickAdd("Read ch 3", estimateMinutes = 45), read("Read ch 3 for 45min").copy(spans = emptyList()))
        assertEquals(QuickAdd("Submit form", date(10, 23), LocalTime.of(17, 0)), read("Submit form by 23/10 5pm").copy(spans = emptyList()))
        assertEquals(QuickAdd("Call 3 shops"), read("Call 3 shops"))
        assertEquals(QuickAdd("Gym", today), read("Gym tue").copy(spans = emptyList()))
        assertEquals(QuickAdd("Plan for Friday", date(10, 23)), read("\"Plan for Friday\" fri").copy(spans = emptyList()))
    }

    @Test
    fun `the spans cover what was read`() {
        val text = "Econ essay draft fri 3pm 2h #EC101"
        val spans = read(text).spans
        assertEquals(listOf("fri", "3pm", "2h", "#EC101"), spans.map { text.substring(it.start, it.end) })
        assertEquals(listOf(QuickAddPart.DATE, QuickAddPart.TIME, QuickAddPart.ESTIMATE, QuickAddPart.MODULE), spans.map { it.part })
    }

    @Test
    fun `dates in words`() {
        assertEquals(today, read("x today").dueDate)
        assertEquals(date(10, 21), read("x tomorrow").dueDate)
        assertEquals(date(10, 21), read("x tmrw").dueDate)
        assertEquals(date(10, 21), read("x tmr").dueDate)
        assertEquals(date(10, 26), read("x monday").dueDate)
        assertEquals(date(10, 26), read("x Mon").dueDate)
        assertEquals(today, read("x tuesday").dueDate, "today counts")
        assertEquals(today, read("x tues").dueDate)
        assertEquals(date(10, 21), read("x weds").dueDate)
        assertEquals(date(10, 22), read("x thurs").dueDate)
        assertEquals(date(10, 22), read("x THU").dueDate)
        assertEquals(date(10, 24), read("x saturday").dueDate)
        assertEquals(date(10, 25), read("x sunday").dueDate)
    }

    @Test
    fun `dates with numbers`() {
        assertEquals(date(10, 23), read("x 23/10").dueDate)
        assertEquals(date(11, 5), read("x 5/11").dueDate)
        assertEquals(date(1, 3, 2027), read("x 3/1").dueDate, "a day already past this year is next year's")
        assertEquals(date(10, 19, 2027), read("x 19/10").dueDate, "yesterday's date is next year's")
        assertEquals(date(12, 25, 2026), read("x 25/12/2026").dueDate)
        assertEquals(date(12, 25, 2026), read("x 25/12/26").dueDate)
        assertEquals(date(10, 23), read("x 23 oct").dueDate)
        assertEquals(date(10, 23), read("x 23rd October").dueDate)
        assertEquals(date(10, 23), read("x oct 23").dueDate)
        assertEquals(date(11, 2), read("x 2nd nov").dueDate)
        assertEquals(date(9, 1, 2027), read("x 1 sept").dueDate)
        assertEquals(date(3, 4, 2027), read("x 4 march 2027").dueDate)
        assertEquals("x", read("x 4 march 2027").title)
        assertEquals(date(2, 29, 2028), read("x 29/2").dueDate, "the next 29 February")
    }

    @Test
    fun `dates that do not exist stay in the title`() {
        assertEquals(QuickAdd("x 31/11"), read("x 31/11"))
        assertEquals(QuickAdd("x 13/13"), read("x 13/13"))
        assertNull(read("x 32 oct").dueDate)
        assertEquals("x 30 feb", read("x 30 feb").title)
    }

    @Test
    fun `times`() {
        assertEquals(LocalTime.of(15, 0), read("x 3pm").dueTime)
        assertEquals(LocalTime.of(15, 30), read("x 3:30pm").dueTime)
        assertEquals(LocalTime.of(15, 30), read("x 3:30 pm").dueTime)
        assertEquals(LocalTime.of(9, 0), read("x 9 am").dueTime)
        assertEquals(LocalTime.of(0, 15), read("x 12:15am").dueTime)
        assertEquals(LocalTime.of(12, 0), read("x 12pm").dueTime)
        assertEquals(LocalTime.of(15, 0), read("x 15:00").dueTime)
        assertEquals(LocalTime.of(9, 5), read("x 09:05").dueTime)
        assertEquals(LocalTime.NOON, read("x noon").dueTime)
        assertEquals(LocalTime.NOON, read("x midday").dueTime)
        assertEquals(today, read("x 3pm").dueDate, "a time without a date is today's")
        assertEquals("x 13pm", read("x 13pm").title)
        assertEquals("x 25:00", read("x 25:00").title)
        assertNull(read("Call at 15").dueTime, "a bare number is never a time")
    }

    @Test
    fun `estimates`() {
        assertEquals(120, read("x 2h").estimateMinutes)
        assertEquals(90, read("x 90m").estimateMinutes)
        assertEquals(45, read("x 45min").estimateMinutes)
        assertEquals(90, read("x 1h30").estimateMinutes)
        assertEquals(90, read("x 1h30m").estimateMinutes)
        assertEquals(90, read("x 1.5h").estimateMinutes)
        assertEquals(120, read("x 2 hrs").estimateMinutes)
        assertEquals(30, read("x 30 minutes").estimateMinutes)
        assertEquals("x", read("x 2 hrs").title)
        assertNull(read("x 0h").estimateMinutes)
        assertNull(read("x 300h").estimateMinutes, "over 200 hours is not an estimate")
    }

    @Test
    fun `modules, tags and priority`() {
        assertEquals(7L, read("x #ec101").moduleId, "codes match without case")
        assertEquals(8L, read("x #MA102,").moduleId)
        assertEquals(listOf("reading"), read("x #reading").tags)
        val both = read("x #EC101 #MA102 #group")
        assertEquals(7L, both.moduleId, "the first module counts")
        assertEquals(listOf("MA102", "group"), both.tags, "later module codes become tags")
        assertEquals(Priority.LOW, read("x !").priority)
        assertEquals(Priority.MEDIUM, read("x !!").priority)
        assertEquals("Wow!", read("Wow!").title, "an exclamation inside a word is title")
        assertEquals("x #", read("x #").title)
    }

    @Test
    fun `only the first of each kind counts and the rest stay in the title`() {
        val r = read("Move lecture mon to tue 3pm 4pm")
        assertEquals(date(10, 26), r.dueDate)
        assertEquals(LocalTime.of(15, 0), r.dueTime)
        assertEquals("Move lecture to tue 4pm", r.title, "only connectors just before a read word go")
    }

    @Test
    fun `connectors go with what follows them`() {
        assertEquals("Meet Sam at library", read("Meet Sam at 3pm at library").title)
        assertEquals("Essay", read("Essay due fri").title)
        assertEquals("Ring mum", read("Ring mum on sunday").title)
        assertEquals("Hand in for", read("Hand in for").title, "a connector with nothing read after it stays")
    }

    @Test
    fun `sat and sun are days only at the end or before something read`() {
        assertEquals(QuickAdd("Buy sun cream"), read("Buy sun cream"))
        assertEquals(QuickAdd("SAT practice"), read("SAT practice"))
        assertEquals(date(10, 24), read("Gym sat").dueDate)
        assertEquals(date(10, 25), read("Laundry sun 10am").dueDate)
        assertEquals("Laundry", read("Laundry sun 10am").title)
    }

    @Test
    fun `quotes`() {
        val quoted = read("Revise \"for\" fri")
        assertEquals("Revise for", quoted.title, "a quoted connector stays")
        assertEquals(date(10, 23), quoted.dueDate)
        assertEquals("Read tomorrow's notes", read("Read “tomorrow's notes”").title)
        assertEquals("Unclosed tomorrow", read("Unclosed \"tomorrow").title, "an unclosed quote keeps the rest as title")
        assertNull(read("Unclosed \"tomorrow").dueDate)
    }

    @Test
    fun `punctuation around words`() {
        assertEquals(date(10, 23), read("Essay, fri.").dueDate)
        assertEquals("Essay,", read("Essay, fri.").title)
    }

    @Test
    fun `empty and blank text`() {
        assertEquals(QuickAdd(""), read(""))
        assertEquals(QuickAdd(""), read("   "))
        assertEquals("", read("tomorrow 3pm").title, "nothing left for the title")
        assertEquals(date(10, 21), read("tomorrow 3pm").dueDate)
    }

    @Test
    fun `random text never throws and the title is made of the text's words`() {
        val pieces = listOf(
            "fri", "3pm", "2h", "#EC101", "tomorrow", "\"", "“", "”", "!!!", "23/10", "31/2", "oct", "23rd", "1h30", "noon",
            "at", "for", "sun", "sat", "12:61", "99:99", "4", "pm", "am", "min", "0", "-", "#", "x", "é", "", "  ", "\t",
            "1.5", "h", "2026", "29/2", "13/13/13",
        )
        val random = Random(20261020)
        repeat(5_000) {
            val text = (1..random.nextInt(0, 9)).joinToString(if (random.nextBoolean()) " " else "") { pieces.random(random) }
            val r = read(text)
            for (word in r.title.split(' ').filter { it.isNotEmpty() }) {
                assertTrue(text.contains(word.trim('"', '“', '”')), "'$word' comes from '$text'")
            }
            r.estimateMinutes?.let { assertTrue(it in 1..12_000) }
            if (r.dueTime != null) assertTrue(r.dueDate != null)
        }
    }
}
