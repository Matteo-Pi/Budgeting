package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.insights.EfficiencyRules
import io.github.matteopi.budgeting.core.planner.insights.Period
import io.github.matteopi.budgeting.core.planner.insights.TipInput
import io.github.matteopi.budgeting.core.planner.insights.TipKind
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The efficiency tips (docs/PLAN.md §13.4): each fires on its fixture and stays quiet on its control. */
class EfficiencyRulesTest {

    private val zone = ZoneId.of("Europe/London")
    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    private fun id(key: CategoryKey) = categories.single { it.systemKey == key }.id

    /** Four weeks, Monday 28 Sep to Sunday 25 Oct 2026 (the clocks go back on the last day). */
    private val period = Period(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 25))
    private val now = ZonedDateTime.of(LocalDate.of(2026, 10, 26).atTime(9, 0), zone).toInstant()
    private val weekdays = period.dates.filter { it.dayOfWeek <= DayOfWeek.FRIDAY }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant()

    private fun chunk(date: LocalDate, hour: Int, minutes: Long, key: CategoryKey, focus: Int? = null, minute: Int = 0) =
        RecordChunk(start = at(date, hour, minute), end = at(date, hour, minute).plusSeconds(minutes * 60), categoryId = id(key), focus = focus)

    private fun input(records: List<RecordChunk> = emptyList(), plans: List<PlanBlock> = emptyList(), occurrences: List<Occurrence> = emptyList()) =
        TipInput(period, records, plans, occurrences, categories, now, zone)

    @Test
    fun `best hours fire when mornings rate high and half the study is in poor evenings`() {
        val mornings = weekdays.take(6).map { chunk(it, 9, 60, CategoryKey.STUDY, focus = 5) }
        val evenings = weekdays.drop(6).take(6).map { chunk(it, 19, 60, CategoryKey.STUDY, focus = 2) }
        val tip = assertNotNull(EfficiencyRules.bestHours(input(mornings + evenings)))
        assertEquals(TipKind.BEST_HOURS, tip.kind)
        assertEquals(
            "You focus best 08:00–10:00. 50% of your study is at 18:00–20:00, rated 2.0 on average: try moving some of it to your best hours.",
            tip.text,
        )
        assertEquals("08:00–10:00: 5.0 over 6 ratings · overall 3.5 · 6 h of 12 h at 2.0", tip.numbers)

        // Control: the same study, all rated 4.
        assertNull(EfficiencyRules.bestHours(input((mornings + evenings).map { it.copy(focus = 4) })))
        // Too few ratings in the best bucket.
        assertNull(EfficiencyRules.bestHours(input(mornings.take(4) + evenings)))
    }

    @Test
    fun `shorter blocks fire when long ones rate lower or get skipped`() {
        val long = weekdays.take(3).map { chunk(it, 14, 90, CategoryKey.STUDY, focus = 2) }
        val short = weekdays.drop(3).take(3).map { chunk(it, 14, 40, CategoryKey.STUDY, focus = 4) }
        val tip = assertNotNull(EfficiencyRules.shorterBlocks(input(long + short)))
        assertEquals("Over 75 m: 2.0 over 3 ratings · 45 m or less: 4.0 over 3", tip.numbers)
        assertNull(EfficiencyRules.shorterBlocks(input(long.map { it.copy(focus = 4) } + short)), "control: long blocks rate as well")

        // Four 90-minute plans, two of them skipped.
        val plans = weekdays.take(4).mapIndexed { i, d ->
            PlanBlock(id = i + 1L, start = at(d, 10), end = at(d, 11, 30), categoryId = id(CategoryKey.STUDY))
        }
        val done = plans.take(2).map { RecordChunk(start = it.start, end = it.end, categoryId = it.categoryId) }
        val skipped = assertNotNull(EfficiencyRules.shorterBlocks(input(done, plans)))
        assertEquals("Over 75 m: 2 of 4 skipped", skipped.numbers)
        assertTrue(skipped.text.startsWith("You skip 50% of the study blocks"))
        val allDone = plans.map { RecordChunk(start = it.start, end = it.end, categoryId = it.categoryId) }
        assertNull(EfficiencyRules.shorterBlocks(input(allDone, plans)), "control: every long block done")
    }

    private fun lecture(date: LocalDate, start: LocalTime, end: LocalTime, entry: Long) = Occurrence(
        entryId = entry, date = date, start = at(date, start.hour, start.minute), end = at(date, end.hour, end.minute), startTime = start, endTime = end,
        title = "Class $entry", kind = EntryKind.LECTURE, moduleId = null, location = "Campus", room = "", colorArgb = null,
    )

    @Test
    fun `a recurring gap between classes spent on the phone fires`() {
        val tuesdays = period.dates.filter { it.dayOfWeek == DayOfWeek.TUESDAY }
        val classes = tuesdays.flatMap { listOf(lecture(it, LocalTime.of(9, 0), LocalTime.of(11, 0), 1), lecture(it, LocalTime.of(12, 30), LocalTime.of(14, 0), 2)) }
        val phone = tuesdays.map { chunk(it, 11, 75, CategoryKey.PHONE) }
        val tip = assertNotNull(EfficiencyRules.classGaps(input(phone, occurrences = classes)))
        assertEquals("Your Tuesday gap between classes, 11:00–12:30, has mostly gone on phone and rest: it is long enough for a study block.", tip.text)
        assertEquals("4 weeks · 5 h of 6 h on phone or rest", tip.numbers)

        assertNull(EfficiencyRules.classGaps(input(tuesdays.map { chunk(it, 11, 75, CategoryKey.STUDY) }, occurrences = classes)), "control: the gap is used")
        assertNull(EfficiencyRules.classGaps(input(phone.take(2), occurrences = classes.take(4))), "two weeks are not a pattern")
        val long = tuesdays.flatMap { listOf(lecture(it, LocalTime.of(9, 0), LocalTime.of(10, 0), 1), lecture(it, LocalTime.of(14, 0), LocalTime.of(15, 0), 2)) }
        assertNull(EfficiencyRules.classGaps(input(phone, occurrences = long)), "a four-hour gap is not a gap between classes")
    }

    @Test
    fun `commute revision fires from three hours of travel a week`() {
        val travel = weekdays.map { chunk(it, 8, 45, CategoryKey.TRAVEL) }
        val tip = assertNotNull(EfficiencyRules.commute(input(travel)))
        assertEquals("You travel 3 h 45 m a week: flashcards, reading or a lecture recording could make some of it revision.", tip.text)
        assertEquals("15 h of travel over 4 weeks", tip.numbers)
        assertNull(EfficiencyRules.commute(input(weekdays.map { chunk(it, 8, 30, CategoryKey.TRAVEL) })), "control: 2 h 30 m a week")
    }

    @Test
    fun `batching chores fires on many short chunks`() {
        val days = period.dates.filter { it.dayOfWeek != DayOfWeek.SUNDAY }
        val short = days.map { chunk(it, 18, 15, CategoryKey.CHORES) }
        val tip = assertNotNull(EfficiencyRules.chores(input(short)))
        assertEquals("24 chunks over 4 weeks · 15 m each", tip.numbers)
        assertNull(EfficiencyRules.chores(input(days.map { chunk(it, 18, 30, CategoryKey.CHORES) })), "control: longer chunks")
        assertNull(EfficiencyRules.chores(input(short.take(12))), "control: three a week")
    }

    @Test
    fun `phone time fires at an hour and a half a day, or up a quarter on the week before`() {
        val lastWeek = Period(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25))
        val heavy = lastWeek.dates.map { chunk(it, 21, 120, CategoryKey.PHONE) }
        val tip = assertNotNull(EfficiencyRules.phone(input(heavy)))
        assertEquals("Phone and scrolling took 2 h a day in the last week.", tip.text)
        assertEquals("14 h in 7 days · 2 h a day", tip.numbers)

        val before = lastWeek.previous().dates.map { chunk(it, 21, 40, CategoryKey.PHONE) }
        val rising = lastWeek.dates.map { chunk(it, 21, 60, CategoryKey.PHONE) }
        val up = assertNotNull(EfficiencyRules.phone(input(before + rising)))
        assertEquals("Phone and scrolling took 7 h in the last week, up 50% on the week before.", up.text)
        assertEquals("7 h in 7 days · 1 h a day · the week before: 4 h 40 m", up.numbers)

        val steady = (lastWeek.previous().dates + lastWeek.dates).map { chunk(it, 21, 30, CategoryKey.PHONE) }
        assertNull(EfficiencyRules.phone(input(steady)), "control: half an hour a day, as before")
    }

    @Test
    fun `nothing recorded gives no tips`() {
        assertTrue(EfficiencyRules.all(input()).isEmpty())
    }
}
