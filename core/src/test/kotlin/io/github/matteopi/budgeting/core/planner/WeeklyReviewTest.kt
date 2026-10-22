package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.insights.OutcomeCounts
import io.github.matteopi.budgeting.core.planner.insights.Period
import io.github.matteopi.budgeting.core.planner.insights.WeeklyReview
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.reminders.ReminderKind
import io.github.matteopi.budgeting.core.planner.reminders.ReminderPlan
import io.github.matteopi.budgeting.core.planner.suggest.Demand
import io.github.matteopi.budgeting.core.planner.suggest.DemandKind
import io.github.matteopi.budgeting.core.planner.suggest.StudyInput
import io.github.matteopi.budgeting.core.planner.suggest.StudyPlanner
import io.github.matteopi.budgeting.core.planner.suggest.StudyPrefs
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import io.github.matteopi.budgeting.core.planner.suggest.TimeWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The weekly review (docs/PLAN.md §13.4): its weeks, its numbers, *Move*'s other times and its reminder. */
class WeeklyReviewTest {

    private val zone = ZoneId.of("Europe/London")
    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    private fun id(key: CategoryKey) = categories.single { it.systemKey == key }.id
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)
    private fun at(day: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(oct(day).atTime(h, m), zone).toInstant()

    @Test
    fun `up to Wednesday it reviews last week, from Thursday this one`() {
        val thisWeek = Period(oct(19), oct(25))
        val lastWeek = Period(oct(12), oct(18))
        val nextWeek = Period(oct(26), LocalDate.of(2026, 11, 1))
        assertEquals(lastWeek to thisWeek, WeeklyReview.weeks(oct(19)))
        assertEquals(lastWeek to thisWeek, WeeklyReview.weeks(oct(21)))
        assertEquals(thisWeek to nextWeek, WeeklyReview.weeks(oct(22)))
        assertEquals(thisWeek to nextWeek, WeeklyReview.weeks(oct(25)))
    }

    @Test
    fun `Sunday evening's review of the week, and next week's suggestions by day`() {
        val today = oct(25) // Sunday: the clocks went back this morning
        val now = at(25, 19)
        val study = id(CategoryKey.STUDY)
        val plans = listOf(
            PlanBlock(id = 1, start = at(19, 10), end = at(19, 11, 30), categoryId = study),
            PlanBlock(id = 2, start = at(21, 14), end = at(21, 15), categoryId = study),
            PlanBlock(id = 3, start = at(26, 9), end = at(26, 10), categoryId = study), // next week's
        )
        val records = listOf(
            RecordChunk(start = at(19, 10), end = at(19, 11, 30), categoryId = study, taskId = 9),
            RecordChunk(start = at(20, 20), end = at(20, 22), categoryId = study),
            RecordChunk(start = at(22, 21), end = at(22, 23), categoryId = id(CategoryKey.PHONE)),
            RecordChunk(start = at(23, 9), end = at(23, 12), categoryId = id(CategoryKey.CLASS)),
            RecordChunk(start = at(24, 18), end = at(24, 19), categoryId = id(CategoryKey.EXERCISE)),
            RecordChunk(start = at(24, 19), end = at(24, 19, 30), categoryId = id(CategoryKey.COOKING)),
        )
        val window = { d: LocalDate -> DayWindows.recordWindow(d, DaySettings(), zone) }
        val gym = Habit(id = 1, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 2), startDate = oct(12))
        val status = HabitProgress.evaluate(gym, listOf(HabitEntry(habitId = 1, date = oct(20), amount = 1), HabitEntry(habitId = 1, date = oct(24), amount = 1)),
            records, emptyList(), emptyList(), window, oct(12), today, today)
        val essay = Task(id = 9, title = "EC101 essay", kind = TaskKind.DEADLINE, dueDate = oct(30), estimateMinutes = 300)
        val later = Task(id = 10, title = "MA102 project", kind = TaskKind.DEADLINE, dueDate = LocalDate.of(2026, 11, 20), estimateMinutes = 600)
        val demand = Demand(key = "deadline:9", kind = DemandKind.DEADLINE, title = "EC101 essay", minutes = 210, latest = oct(29), taskId = 9, reason = "")
        fun suggestion(start: Instant, minutes: Long) = Suggestion("deadline:9@${start.toEpochMilli()}", Interval(start, start.plusSeconds(minutes * 60)), demand, "")
        val suggestions = listOf(suggestion(at(27, 9), 90), suggestion(at(26, 14), 90), suggestion(at(27, 14), 30))

        val review = WeeklyReview.build(today, now, zone, DaySettings(), records, plans, categories, listOf(status), listOf(essay, later),
            mapOf(9L to 90), suggestions)

        assertEquals(Period(oct(19), oct(25)), review.reviewed)
        assertEquals(150L, review.plannedMinutes, "the two blocks of the week, not next week's")
        assertEquals(210L, review.studiedMinutes)
        assertEquals(OutcomeCounts(done = 1, skipped = 1), review.outcomes)
        assertEquals(listOf(id(CategoryKey.STUDY), id(CategoryKey.PHONE), id(CategoryKey.EXERCISE)), review.topCategories.map { it.categoryId },
            "classes are left out")
        assertEquals(listOf(1.0), review.habits.map { it.rate })
        assertEquals(listOf("EC101 essay"), review.deadlines.map { it.task.title }, "the project is over 14 days away")
        assertEquals(7, review.nextWeek.size)
        assertEquals(oct(26), review.nextWeek.first().first)
        assertEquals(listOf(listOf(at(26, 14)), listOf(at(27, 9), at(27, 14))), review.nextWeek.take(2).map { (_, list) -> list.map { it.interval.start } })
        assertTrue(review.nextWeek.drop(2).all { it.second.isEmpty() })
    }

    @Test
    fun `move offers the demand's other free pieces, best first, without its own time or the other suggestions'`() {
        val prefs = StudyPrefs(preferred = listOf(TimeWindow(9 * 60, 18 * 60)))
        val demand = Demand(key = "deadline:7", kind = DemandKind.DEADLINE, title = "EC101 essay", minutes = 90, latest = oct(23), taskId = 7, reason = "")
        val free = listOf(Interval(at(20, 13), at(20, 17)), Interval(at(21, 9), at(21, 12)), Interval(at(21, 19), at(21, 21)))
        val input = StudyInput(at(20, 12), zone, free, listOf(demand), prefs)
        val s = StudyPlanner.suggest(input).single()
        assertEquals(Interval(at(20, 13), at(20, 14, 30)), s.interval)
        // Inside the preferred times first, then the evening.
        assertEquals(
            listOf(Interval(at(20, 14, 30), at(20, 16)), Interval(at(21, 9), at(21, 10, 30)), Interval(at(21, 19), at(21, 20, 30))),
            StudyPlanner.alternatives(input, s, listOf(s)),
        )
        val other = Suggestion("module:3@1", Interval(at(21, 9), at(21, 11)), demand.copy(key = "module:3"), "")
        assertEquals(
            listOf(Interval(at(20, 14, 30), at(20, 16)), Interval(at(21, 19), at(21, 20, 30))),
            StudyPlanner.alternatives(input, s, listOf(s, other)),
            "an hour left after the other suggestion is too short",
        )
        assertEquals(1, StudyPlanner.alternatives(input, s, listOf(s), limit = 1).size)
    }

    @Test
    fun `the weekly review reminds this week and next at its day and time, or not at all`() {
        val time = LocalTime.of(19, 0)
        val reminders = ReminderPlan.weeklyReviewReminders(DayOfWeek.SUNDAY, time, oct(21), zone)
        assertEquals(listOf(at(25, 19), ZonedDateTime.of(LocalDate.of(2026, 11, 1).atTime(19, 0), zone).toInstant()), reminders.map { it.at })
        assertTrue(reminders.all { it.kind == ReminderKind.WEEKLY_REVIEW })
        assertEquals("weekly:2026-10-25:19:00", reminders.first().key)
        assertEquals(oct(25).toEpochDay(), reminders.first().itemId)
        // On the day itself, after the time, the next one is a week away.
        val sunday = ReminderPlan.weeklyReviewReminders(DayOfWeek.SUNDAY, time, oct(25), zone)
        assertEquals(at(25, 19), sunday.first().at)
        assertEquals(ZonedDateTime.of(LocalDate.of(2026, 11, 1).atTime(19, 0), zone).toInstant(), ReminderPlan.next(sunday, at(25, 20))?.at)
        assertTrue(ReminderPlan.weeklyReviewReminders(DayOfWeek.SUNDAY, null, oct(21), zone).isEmpty())
    }
}
