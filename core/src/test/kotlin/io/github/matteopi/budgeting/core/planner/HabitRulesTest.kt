package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.habits.HabitField
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.habits.HabitRules
import io.github.matteopi.budgeting.core.planner.habits.HabitText
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.reminders.ReminderKind
import io.github.matteopi.budgeting.core.planner.reminders.ReminderPlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a habit needs to be saved, how habits read, and their reminders (docs/PLAN.md §12.9, §13.4). */
class HabitRulesTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 20)
    private val window = { date: LocalDate -> DayWindows.recordWindow(date, DaySettings(), zone) }
    private val gym = Habit(id = 1, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), startDate = LocalDate.of(2026, 10, 5))

    private fun status(habit: Habit, entries: List<HabitEntry> = emptyList()) =
        HabitProgress.evaluate(habit, entries, emptyList(), emptyList(), emptyList(), window, today.minusDays(7), today.plusDays(1), today)

    @Test
    fun `a habit needs a name, a positive target, its days and a category to count`() {
        assertTrue(HabitRules.isValid(gym))
        assertEquals(setOf(HabitField.NAME), HabitRules.errors(gym.copy(name = " ")).keys)
        assertEquals(setOf(HabitField.TARGET), HabitRules.errors(gym.copy(goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 0))).keys)
        assertEquals(setOf(HabitField.DAYS), HabitRules.errors(gym.copy(goal = HabitGoal(GoalKind.ON_DAYS))).keys)
        assertEquals(setOf(HabitField.CATEGORY), HabitRules.errors(gym.copy(counting = HabitCounting.BOTH)).keys)
        val daily = gym.copy(goal = HabitGoal(GoalKind.AT_MOST, 25 * 60, GoalUnit.MINUTES, GoalPeriod.DAY))
        assertEquals(setOf(HabitField.TARGET), HabitRules.errors(daily).keys, "more than a day's minutes in a day")
        assertTrue(HabitRules.isValid(daily.copy(goal = daily.goal.copy(period = GoalPeriod.WEEK))))
        assertEquals(setOf(HabitField.MIN_MINUTES), HabitRules.errors(gym.copy(minMinutes = 0)).keys)
        assertEquals(setOf(HabitField.PAUSE), HabitRules.errors(gym.copy(pausedUntil = today)).keys)
        assertEquals(setOf(HabitField.PAUSE), HabitRules.errors(gym.copy(pausedFrom = today, pausedUntil = today.minusDays(1))).keys)
        assertTrue(HabitRules.isValid(gym.copy(pausedFrom = today)))
    }

    @Test
    fun `goals read as the user set them`() {
        assertEquals("3 times a week", HabitText.goal(gym.goal))
        assertEquals("Once a week", HabitText.goal(HabitGoal(GoalKind.TIMES_PER_WEEK, 1)))
        assertEquals("Every day", HabitText.goal(HabitGoal(GoalKind.ON_DAYS, days = DayOfWeek.entries.toSet())))
        assertEquals("On Mon, Wed, Fri", HabitText.goal(HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))))
        assertEquals("20 m a day", HabitText.goal(HabitGoal(GoalKind.AT_LEAST, 20, GoalUnit.MINUTES)))
        assertEquals("At most 1 h a day", HabitText.goal(HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES)))
        assertEquals("2 h 30 m a week", HabitText.goal(HabitGoal(GoalKind.AT_LEAST, 150, GoalUnit.MINUTES, GoalPeriod.WEEK)))
        assertEquals("10 a day on Sat, Sun", HabitText.goal(HabitGoal(GoalKind.AT_LEAST, 10, GoalUnit.COUNT, days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))))
        assertEquals("2 times a day", HabitText.goal(HabitGoal(GoalKind.AT_LEAST, 2)))
    }

    @Test
    fun `progress and streaks read for the period`() {
        val ticks = listOf(5, 7, 9, 19).map { HabitEntry(habitId = 1, date = LocalDate.of(2026, 10, it), amount = 1) }
        val weekly = status(gym, ticks)
        assertEquals("1 of 3 this week", HabitText.progress(weekly))
        assertEquals("Streak 0 weeks · best 1", HabitText.streak(weekly))
        assertNull(HabitText.streak(status(gym.copy(streaks = false))))

        val read = Habit(id = 2, name = "Read", goal = HabitGoal(GoalKind.AT_LEAST, 20, GoalUnit.MINUTES), startDate = today.minusDays(1))
        assertEquals("15 m of 20 m today", HabitText.progress(status(read, listOf(HabitEntry(habitId = 2, date = today, amount = 15)))))
        assertEquals("Skipped today", HabitText.progress(status(read, listOf(HabitEntry(habitId = 2, date = today, skipped = true)))))
        assertEquals("Paused", HabitText.progress(status(read.copy(pausedFrom = today))))
        assertEquals("Streak 1 day · best 1", HabitText.streak(status(read, listOf(HabitEntry(habitId = 2, date = today, amount = 20)))))

        val limit = read.copy(goal = HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES))
        assertEquals("1 h 15 m of 1 h today · over the limit", HabitText.progress(status(limit, listOf(HabitEntry(habitId = 2, date = today, amount = 75)))))

        val tuesdays = read.copy(goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.TUESDAY)))
        assertEquals("Not done yet today", HabitText.progress(status(tuesdays)))
        assertEquals("Done today", HabitText.progress(status(tuesdays, listOf(HabitEntry(habitId = 2, date = today, amount = 1)))))
        assertEquals("Rest day", HabitText.progress(status(tuesdays.copy(goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.MONDAY))))))
        assertEquals("Not started yet", HabitText.progress(status(read.copy(startDate = today.plusDays(3)))))
    }

    @Test
    fun `a tick enters a time, or a daily amount's target`() {
        assertEquals(1, HabitProgress.tickAmount(gym.goal))
        assertEquals(1, HabitProgress.tickAmount(HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.MONDAY))))
        assertEquals(20, HabitProgress.tickAmount(HabitGoal(GoalKind.AT_LEAST, 20, GoalUnit.MINUTES)))
        assertNull(HabitProgress.tickAmount(HabitGoal(GoalKind.AT_LEAST, 150, GoalUnit.MINUTES, GoalPeriod.WEEK)))
        assertNull(HabitProgress.tickAmount(HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES)))
        val limit = Habit(id = 3, name = "Phone", goal = HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES), reminderTime = LocalTime.of(21, 0), startDate = today)
        assertTrue(ReminderPlan.habitReminders(listOf(status(limit)), today, zone).none { it.offersDone }, "a limit isn't ticked off")
    }

    @Test
    fun `a habit reminds on its days still to do, today and tomorrow`() {
        val read = Habit(id = 2, name = "Read", goal = HabitGoal(GoalKind.AT_LEAST, 20, GoalUnit.MINUTES), reminderTime = LocalTime.of(21, 30), startDate = today)
        val open = ReminderPlan.habitReminders(listOf(status(read)), today, zone)
        assertEquals(listOf(today, today.plusDays(1)).map { ZonedDateTime.of(it, LocalTime.of(21, 30), zone).toInstant() }, open.map { it.at })
        assertTrue(open.all { it.kind == ReminderKind.HABIT && it.itemId == 2L && it.title == "Read" && it.offersDone })
        assertEquals("0 m of 20 m today", open.first().text)

        val done = ReminderPlan.habitReminders(listOf(status(read, listOf(HabitEntry(habitId = 2, date = today, amount = 30)))), today, zone)
        assertEquals(listOf(today.plusDays(1)), done.map { it.at.atZone(zone).toLocalDate() }, "done today: only tomorrow's")

        val weekDone = gym.copy(reminderTime = LocalTime.of(18, 0))
        val monday = HabitEntry(habitId = 1, date = LocalDate.of(2026, 10, 19), amount = 1)
        assertEquals(2, ReminderPlan.habitReminders(listOf(status(weekDone, listOf(monday))), today, zone).size, "1 of 3 so far")
        val three = listOf(19, 20).map { HabitEntry(habitId = 1, date = LocalDate.of(2026, 10, it), amount = if (it == 19) 2 else 1) }
        assertTrue(ReminderPlan.habitReminders(listOf(status(weekDone, three)), today, zone).isEmpty(), "this week's 3 are done")

        assertTrue(ReminderPlan.habitReminders(listOf(status(read.copy(reminderTime = null))), today, zone).isEmpty())
        assertTrue(ReminderPlan.habitReminders(listOf(status(read.copy(archived = true))), today, zone).isEmpty())
        val restTomorrow = read.copy(goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.TUESDAY)))
        assertEquals(1, ReminderPlan.habitReminders(listOf(status(restTomorrow)), today, zone).size, "Wednesday is a rest day")
    }
}
