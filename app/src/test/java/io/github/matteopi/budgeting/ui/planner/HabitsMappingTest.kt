package io.github.matteopi.budgeting.ui.planner

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.habits.HabitDayState
import io.github.matteopi.budgeting.core.planner.habits.HabitField
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.domain.planner.HabitsModel
import io.github.matteopi.budgeting.ui.planner.habits.HabitForm
import io.github.matteopi.budgeting.ui.planner.habits.HabitsMapping
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Habits tab, the habit page and the editor (docs/PLAN.md §12.9, §13.9), on Tue 20 Oct 2026. */
class HabitsMappingTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 20)
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)
    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    private val exercise = categories.single { it.systemKey == CategoryKey.EXERCISE }

    private val gym = Habit(id = 1, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), startDate = oct(5), sortOrder = 0)
    private val read = Habit(id = 2, name = "Read", goal = HabitGoal(GoalKind.AT_LEAST, 20, GoalUnit.MINUTES), startDate = oct(12), sortOrder = 1)
    private val phone = Habit(id = 3, name = "Phone", goal = HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES), startDate = oct(19), sortOrder = 2)
    private val weekends = Habit(id = 4, name = "Long run", goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.SATURDAY)), startDate = oct(1), sortOrder = 3)
    private val old = Habit(id = 5, name = "Old", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 1), startDate = oct(1), archived = true, sortOrder = 4)

    private val entries = listOf(5, 7, 9, 12, 14, 16, 19).map { HabitEntry(habitId = 1, date = oct(it), amount = 1) } +
        HabitEntry(habitId = 2, date = today, amount = 15) + HabitEntry(habitId = 3, date = today, amount = 75)

    private fun model(vararg habits: Habit, extra: List<HabitEntry> = emptyList()) = HabitsModel(
        today = today,
        statuses = habits.map {
            HabitProgress.evaluate(it, entries + extra, emptyList(), emptyList(), emptyList(), { d -> DayWindows.recordWindow(d, DaySettings(), zone) },
                it.startDate, oct(31), today)
        },
        categories = categories,
    )

    @Test
    fun `the tab lists today's habits, then the rest, then the archived`() {
        val sections = HabitsMapping.sections(model(weekends, phone, gym, read, old))
        assertEquals(listOf("Gym", "Read", "Phone"), sections.today.map { it.name }, "in their order")
        assertEquals(listOf("Long run"), sections.other.map { it.name }, "a Saturday habit rests on Tuesday")
        assertEquals(listOf("Old"), sections.archived.map { it.name })
        assertEquals(sections.today, HabitsMapping.forToday(model(weekends, phone, gym, read, old)))
    }

    @Test
    fun `a row says how the period stands and whether it ticks or takes an amount`() {
        val rows = HabitsMapping.sections(model(gym, read, phone)).today.associateBy { it.name }
        val g = rows.getValue("Gym")
        assertEquals("3 times a week", g.goal)
        assertEquals("1 of 3 this week", g.progress)
        assertEquals("Streak 2 weeks · best 2", g.streak)
        assertTrue(g.tickable)
        assertFalse(g.ticked, "nothing today yet")
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), g.week.map { it.letter })
        assertEquals(listOf(HabitDayState.DONE, HabitDayState.PENDING), g.week.take(2).map { it.state })
        assertTrue(g.week[1].isToday)
        assertEquals("Gym, 3 times a week, 1 of 3 this week, Streak 2 weeks · best 2", g.description)

        val r = rows.getValue("Read")
        assertTrue(r.tickable, "a daily amount ticks to its target")
        assertFalse(r.ticked, "15 of 20 minutes isn't done")
        assertEquals("15 m of 20 m today", r.progress)

        val p = rows.getValue("Phone")
        assertFalse(p.tickable)
        assertTrue(p.addsAmount, "a limit takes an amount")
        assertTrue(p.over)
        assertEquals("1 h 15 m of 1 h today · over the limit", p.progress)

        val recorded = HabitsMapping.row(model(gym.copy(counting = HabitCounting.RECORD, categoryId = exercise.id)).statuses.single())
        assertFalse(recorded.tickable || recorded.addsAmount, "the record fills it in")
        val skipped = HabitsMapping.row(model(read, extra = listOf(HabitEntry(habitId = 2, date = today, skipped = true))).statuses.single())
        assertTrue(skipped.skipped && skipped.activeToday)
    }

    @Test
    fun `the habit page shows the month Monday first, the rates and recent weeks`() {
        val status = model(gym).statuses.single()
        val month = HabitsMapping.month(status, YearMonth.of(2026, 10))
        assertEquals("October 2026", month.title)
        // 1 Oct 2026 is a Thursday: three blanks first; 31 days fill five weeks.
        assertEquals(5, month.weeks.size)
        assertEquals(listOf("", "", "", "1"), month.weeks.first().take(4).map { it.label })
        val fifth = month.weeks.flatten().single { it.date == oct(5) }
        assertEquals(HabitDayState.DONE, fifth.state)
        assertEquals("Mon 5 Oct, Done", fifth.description)
        assertTrue(month.weeks.flatten().single { it.date == today }.isToday)
        assertNull(month.weeks.flatten().single { it.date == oct(2) }.state, "before the start")

        assertEquals(listOf("This month" to "100%", "Last month" to "–", "Since the start" to "100%"), HabitsMapping.rates(status))
        assertEquals(
            listOf("Week of Mon 19 Oct · 1 of 3 · This week", "Week of Mon 12 Oct · 3 of 3 · Done", "Week of Mon 5 Oct · 3 of 3 · Done"),
            HabitsMapping.recentWeeks(status),
        )
        assertTrue(HabitsMapping.recentWeeks(model(read).statuses.single()).isEmpty(), "a daily goal has no weeks")
    }

    @Test
    fun `the page explains how a habit is counted, its reminder and its pause`() {
        val counted = gym.copy(counting = HabitCounting.BOTH, categoryId = exercise.id, minMinutes = 30, reminderTime = LocalTime.of(18, 15),
            termOnly = true, pausedFrom = oct(24), pausedUntil = oct(31), streaks = false)
        assertEquals(
            listOf(
                "By hand and from Exercise in the day record (sessions of 30 m or more); the larger counts",
                "Reminder at 18:15",
                "Term time only",
                "Started Mon 5 Oct",
                "Paused Sat 24 Oct to Sat 31 Oct",
                "Streaks are off",
            ),
            HabitsMapping.details(counted, categories, today),
        )
        assertEquals(listOf("Filled in by hand", "Started Mon 5 Oct"), HabitsMapping.details(gym, categories, today))
        assertFalse(HabitsMapping.pausedNow(counted, today))
        assertTrue(HabitsMapping.pausedNow(counted.copy(pausedFrom = today, pausedUntil = null), today))
    }

    @Test
    fun `the editor's form builds the goal it shows and keeps Save off until it is complete`() {
        val form = HabitForm.new(today, 0xFF2E8B57)
        assertEquals(setOf(HabitField.NAME), form.errors.keys, "every day by default; only the name is missing")
        val gymForm = form.copy(name = "Gym").withKind(GoalKind.TIMES_PER_WEEK)
        assertEquals(HabitGoal(GoalKind.TIMES_PER_WEEK, 3, GoalUnit.TIMES, GoalPeriod.WEEK), gymForm.toHabit().goal)
        assertTrue(gymForm.errors.isEmpty())
        assertEquals(setOf(HabitField.TARGET), gymForm.copy(target = null).errors.keys)

        val limit = form.copy(name = "Phone").withKind(GoalKind.AT_MOST)
        assertEquals(HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES, GoalPeriod.DAY), limit.toHabit().goal, "every day is no days chosen")
        assertEquals(setOf(DayOfWeek.MONDAY), limit.copy(days = setOf(DayOfWeek.MONDAY)).toHabit().goal.days)
        assertEquals(setOf(HabitField.DAYS), limit.copy(days = emptySet()).errors.keys)

        val counted = gymForm.copy(counting = HabitCounting.RECORD, minMinutes = 30)
        assertEquals(setOf(HabitField.CATEGORY), counted.errors.keys)
        assertEquals(30, counted.copy(categoryId = exercise.id).toHabit().minMinutes)
        assertNull(gymForm.copy(minMinutes = 30).toHabit().minMinutes, "no shortest session when ticked by hand")
        assertNull(counted.copy(counting = HabitCounting.MANUAL, categoryId = exercise.id).toHabit().categoryId)

        // Editing keeps what the editor doesn't show.
        val paused = gym.copy(goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3, GoalUnit.TIMES, GoalPeriod.WEEK), pausedFrom = oct(24), suggestSlot = true, sortOrder = 7)
        val edited = HabitForm.of(paused).copy(name = "Gym and swim").toHabit()
        assertEquals(paused.copy(name = "Gym and swim"), edited)
    }
}
