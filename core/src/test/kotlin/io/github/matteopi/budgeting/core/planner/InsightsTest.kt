package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.DayMode
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.insights.BlockLength
import io.github.matteopi.budgeting.core.planner.insights.DayPart
import io.github.matteopi.budgeting.core.planner.insights.FocusInsights
import io.github.matteopi.budgeting.core.planner.insights.HabitInsights
import io.github.matteopi.budgeting.core.planner.insights.OutcomeCounts
import io.github.matteopi.budgeting.core.planner.insights.Period
import io.github.matteopi.budgeting.core.planner.insights.StudyInsights
import io.github.matteopi.budgeting.core.planner.insights.TimeInsights
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Insights tab's totals (docs/PLAN.md §12.10, §13.4), in Europe/London, across both clock changes of 2026. */
class InsightsTest {

    private val zone = ZoneId.of("Europe/London")
    private val study = 1L
    private val sleep = 2L
    private val phone = 3L
    private val fixed = DaySettings(mode = DayMode.FIXED, cutoffMinute = 4 * 60)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant()

    private fun record(start: Instant, end: Instant, category: Long, module: Long? = null, task: Long? = null, focus: Int? = null) =
        RecordChunk(start = start, end = end, categoryId = category, moduleId = module, taskId = task, focus = focus)

    @Test
    fun `the week the clocks go back has 169 hours, its Saturday 25`() {
        // Sun 25 Oct 2026: 02:00 BST becomes 01:00 GMT. With days from 04:00 to 04:00, Saturday's day holds the extra hour.
        val week = Period.week(LocalDate.of(2026, 10, 21))
        assertEquals(Period(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)), week)
        val all = record(at(week.from, 4), at(week.to.plusDays(1), 4), phone)
        // Sleep from Saturday 23:00 to Sunday 08:00 is ten hours: six in Saturday's day, four in Sunday's.
        val night = record(at(LocalDate.of(2026, 10, 24), 23), at(LocalDate.of(2026, 10, 25), 8), sleep)
        val summary = TimeInsights.summary(listOf(all, night), week, fixed, zone)
        val days = summary.days.associate { it.date to it.minutes }
        assertEquals(169 * 60L, summary.days.sumOf { it.total } - 10 * 60, "the whole week once, and the night on top")
        assertEquals(25 * 60L, days.getValue(LocalDate.of(2026, 10, 24))[phone])
        assertEquals(6 * 60L, days.getValue(LocalDate.of(2026, 10, 24))[sleep])
        assertEquals(4 * 60L, days.getValue(LocalDate.of(2026, 10, 25))[sleep])
        assertEquals(24 * 60L, days.getValue(LocalDate.of(2026, 10, 19))[phone])
        assertEquals(169 * 60L, summary.minutesOf(phone))
        assertEquals(0L, summary.categories.single { it.categoryId == phone }.previousMinutes, "nothing the week before")
    }

    @Test
    fun `the week the clocks go forward has 167 hours, and changes are against the week before`() {
        // Sun 29 Mar 2026: 01:00 GMT becomes 02:00 BST.
        val week = Period.week(LocalDate.of(2026, 3, 25))
        val before = week.previous()
        assertEquals(Period(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 22)), before)
        val records = listOf(
            record(at(week.from, 4), at(week.to.plusDays(1), 4), phone),
            record(at(before.from, 4), at(before.from, 14), phone),
        )
        val summary = TimeInsights.summary(records, week, fixed, zone)
        val time = summary.categories.single()
        assertEquals(167 * 60L, time.minutes)
        assertEquals(10 * 60L, time.previousMinutes)
        assertEquals(157 * 60L, time.change)
        assertEquals(23 * 60L, summary.days.single { it.date == LocalDate.of(2026, 3, 28) }.total)
    }

    @Test
    fun `wake to sleep days leave the night out, and a month compares with the days before it`() {
        val day = DaySettings() // 08:00 to midnight
        val date = LocalDate.of(2026, 10, 14)
        val records = listOf(record(at(date.minusDays(1), 22), at(date, 9), sleep), record(at(date, 9), at(date, 10), study))
        val summary = TimeInsights.summary(records, Period(date, date), day, zone)
        assertEquals(mapOf(sleep to 60L, study to 60L), summary.days.single().minutes, "only 08:00 to 09:00 of the night")
        assertEquals(mapOf(sleep to 120L), TimeInsights.byDay(records, Period(date.minusDays(1), date.minusDays(1)), day, zone).single().minutes)
        val october = Period.month(date)
        assertEquals(31, october.days)
        assertEquals(Period(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30)), october.previous())
        assertTrue(DayWindows.recordWindow(date, day, zone).start == at(date, 8))
    }

    private val ec101 = Module(id = 1, code = "EC101", weeklyTargetMinutes = 300)
    private val ma102 = Module(id = 2, code = "MA102")

    @Test
    fun `modules against their targets, scaled to the period`() {
        val week = Period.week(LocalDate.of(2026, 10, 12))
        val records = listOf(
            record(at(week.from, 9), at(week.from, 11), study, module = 1),
            record(at(week.from.plusDays(2), 14), at(week.from.plusDays(2), 15), study, module = 2),
            record(at(week.from.plusDays(3), 9), at(week.from.plusDays(3), 10), phone, module = 1),
        )
        val rows = StudyInsights.modules(records, listOf(ec101, ma102), setOf(study), week, zone)
        assertEquals(listOf("EC101" to (120L to 300L), "MA102" to (60L to null)), rows.map { it.module.code to (it.minutes to it.targetMinutes) })
        val fortnight = Period(week.from, week.to.plusDays(7))
        assertEquals(600L, StudyInsights.modules(emptyList(), listOf(ec101, ma102), setOf(study), fortnight, zone).single().targetMinutes)
    }

    @Test
    fun `planned study against done, by time of day, weekday and length`() {
        val monday = LocalDate.of(2026, 10, 12)
        val week = Period.week(monday)
        val now = at(monday.plusDays(7), 12)
        val morning = PlanBlock(id = 1, start = at(monday, 9), end = at(monday, 10), categoryId = study)
        val evening = PlanBlock(id = 2, start = at(monday.plusDays(1), 19), end = at(monday.plusDays(1), 20, 30), categoryId = study)
        val afternoon = PlanBlock(id = 3, start = at(monday.plusDays(2), 14), end = at(monday.plusDays(2), 14, 40), categoryId = study)
        val gym = PlanBlock(id = 4, start = at(monday.plusDays(2), 18), end = at(monday.plusDays(2), 19), categoryId = 9)
        val future = PlanBlock(id = 5, start = at(monday.plusDays(8), 9), end = at(monday.plusDays(8), 10), categoryId = study)
        val records = listOf(
            record(morning.start, morning.end, study), // done
            record(afternoon.start, at(monday.plusDays(2), 14, 15), study), // partly
        )
        val stats = StudyInsights.plans(listOf(morning, evening, afternoon, gym, future), records, setOf(study), week, now, zone)
        assertEquals(OutcomeCounts(done = 1, partly = 1, skipped = 1), stats.all)
        assertEquals(1.0 / 3, stats.all.doneRate)
        assertEquals(OutcomeCounts(skipped = 1), stats.byPart[DayPart.EVENING])
        assertEquals(OutcomeCounts(done = 1), stats.byWeekday[DayOfWeek.MONDAY])
        assertEquals(OutcomeCounts(skipped = 1), stats.byLength[BlockLength.LONG])
        assertEquals(OutcomeCounts(partly = 1), stats.byLength[BlockLength.SHORT])
        assertEquals(OutcomeCounts(done = 1), stats.byLength[BlockLength.MEDIUM])
        assertEquals(60L + 90 + 40, stats.plannedMinutes)
        assertNull(OutcomeCounts().doneRate)
    }

    @Test
    fun `a deadline is at risk when the last week's pace won't finish it`() {
        val today = LocalDate.of(2026, 10, 20)
        val essay = Task(id = 1, title = "EC101 essay", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.ASSIGNMENT, dueDate = today.plusDays(4),
            estimateMinutes = 600)
        val report = Task(id = 2, title = "MA102 report", kind = TaskKind.DEADLINE, dueDate = today.plusDays(9), estimateMinutes = 300)
        val step = Task(id = 3, title = "Draft", parentId = 2, estimateMinutes = 300)
        val noEstimate = Task(id = 4, title = "Form", kind = TaskKind.DEADLINE, dueDate = today.plusDays(2))
        // The essay had 1 hour in the last week (8.6 m a day: 43 m in 5 days, short of 540 m); the report's step had 7 hours.
        val records = listOf(
            record(at(today.minusDays(3), 9), at(today.minusDays(3), 10), study, task = 1),
            record(at(today.minusDays(10), 9), at(today.minusDays(10), 19), study, task = 2),
            record(at(today.minusDays(2), 9), at(today.minusDays(2), 16), study, task = 3),
        )
        val studied = mapOf(1L to 60, 3L to 420)
        val rows = StudyInsights.deadlines(listOf(report, essay, step, noEstimate), studied, records, today, zone)
        assertEquals(listOf("Form", "EC101 essay", "MA102 report"), rows.map { it.task.title })
        val e = rows[1]
        assertEquals(60, e.doneMinutes)
        assertEquals(540, e.leftMinutes)
        assertEquals(4L, e.daysLeft)
        assertEquals(60 / 7.0, e.pacePerDay)
        assertTrue(e.atRisk)
        val r = rows[2]
        assertEquals(0, r.leftMinutes, "the step's estimate is done")
        assertFalse(r.atRisk)
        assertNull(rows[0].leftMinutes)
        assertFalse(rows[0].atRisk, "no estimate, no judgement")
    }

    @Test
    fun `focus by time of day and length, from rated study`() {
        val week = Period.week(LocalDate.of(2026, 10, 12))
        val d = week.from
        val records = listOf(
            record(at(d, 9), at(d, 9, 40), study, focus = 5),
            record(at(d, 10), at(d, 11), study, focus = 4),
            record(at(d, 19), at(d, 20, 30), study, focus = 2),
            record(at(d, 21), at(d, 22), study),
            record(at(d, 12), at(d, 13), phone, focus = 1),
        )
        val focus = FocusInsights.summary(records, setOf(study), week, zone)
        assertEquals(3, focus.overall!!.samples)
        assertEquals(11.0 / 3, focus.overall!!.mean)
        assertEquals(setOf(8, 10, 18), focus.byHour.keys)
        assertEquals(5.0, focus.byHour.getValue(8).mean)
        assertEquals(2.0, focus.byLength.getValue(BlockLength.LONG).mean)
        assertEquals(5.0, focus.byLength.getValue(BlockLength.SHORT).mean)
        assertNull(FocusInsights.summary(emptyList(), setOf(study), week, zone).overall)
    }

    @Test
    fun `a habit's four weeks give its rate, streaks and the weekday it slips on`() {
        val today = LocalDate.of(2026, 10, 25) // a Sunday
        val from = today.minusDays(27)
        val run = Habit(id = 1, name = "Run", goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)), startDate = from)
        // Every Wednesday done; Mondays done only once.
        val entries = from.datesUntil(today.plusDays(1)).toList()
            .filter { it.dayOfWeek == DayOfWeek.WEDNESDAY || it == LocalDate.of(2026, 10, 19) }
            .map { HabitEntry(habitId = 1, date = it, amount = 1) }
        val window = { d: LocalDate -> DayWindows.recordWindow(d, DaySettings(), zone) }
        val status = HabitProgress.evaluate(run, entries, emptyList(), emptyList(), emptyList(), window, from, today, today)
        val gym = Habit(id = 2, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), startDate = from)
        val weekly = HabitProgress.evaluate(gym, emptyList(), emptyList(), emptyList(), emptyList(), window, from, today, today)
        val archived = HabitProgress.evaluate(run.copy(id = 3, archived = true), emptyList(), emptyList(), emptyList(), emptyList(), window, from, today, today)
        val rows = HabitInsights.of(listOf(status, weekly, archived), today)
        assertEquals(listOf("Run", "Gym"), rows.map { it.habit.name })
        assertEquals(5.0 / 8, rows[0].rate)
        assertEquals(DayOfWeek.MONDAY, rows[0].mostMissed)
        assertEquals(3, rows[0].missedThatDay)
        assertEquals(status.currentStreak, rows[0].currentStreak)
        assertEquals(0.0, rows[1].rate)
        assertNull(rows[1].mostMissed, "weekly goals have no missed weekday")
    }
}
