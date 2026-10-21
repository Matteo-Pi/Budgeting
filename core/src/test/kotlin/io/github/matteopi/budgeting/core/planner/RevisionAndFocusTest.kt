package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RevisionTopic
import io.github.matteopi.budgeting.core.planner.suggest.DemandKind
import io.github.matteopi.budgeting.core.planner.suggest.Demands
import io.github.matteopi.budgeting.core.planner.suggest.FocusBucket
import io.github.matteopi.budgeting.core.planner.suggest.FocusProfile
import io.github.matteopi.budgeting.core.planner.suggest.SpacedRepetition
import io.github.matteopi.budgeting.core.planner.suggest.StudyInput
import io.github.matteopi.budgeting.core.planner.suggest.StudyPlanner
import io.github.matteopi.budgeting.core.planner.suggest.StudyPrefs
import io.github.matteopi.budgeting.core.planner.suggest.TimeWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Revision at growing intervals, the focus profile and habit slots (docs/PLAN.md §13.4), on Tue 20 Oct 2026. */
class RevisionAndFocusTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 20)
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)
    private fun at(day: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(oct(day).atTime(h, m), zone).toInstant()

    @Test
    fun `a topic comes back after 1, 3, 7, 14 and 30 days, sooner when hard and later when easy`() {
        val topic = RevisionTopic(id = 1, examTaskId = 9, name = "Elasticity")
        val start = oct(1)
        assertEquals(start, SpacedRepetition.nextDue(topic, emptyList(), start), "first due when revision starts")
        assertEquals(oct(3), SpacedRepetition.nextDue(topic, listOf(oct(2)), start))
        assertEquals(oct(8), SpacedRepetition.nextDue(topic, listOf(oct(2), oct(5)), start))
        assertEquals(oct(12), SpacedRepetition.nextDue(topic, listOf(oct(2), oct(5), oct(5)), start), "7 days after the last")
        assertEquals(oct(31), SpacedRepetition.nextDue(topic, List(6) { oct(1) }, start), "30 days from the fifth on")
        assertEquals(oct(6), SpacedRepetition.nextDue(topic.copy(difficulty = 1), listOf(oct(2), oct(2)), start), "3 × 1.3 = 4 days when easy")
        assertEquals(oct(4), SpacedRepetition.nextDue(topic.copy(difficulty = 3), listOf(oct(2), oct(2)), start), "3 × 0.7 = 2 days when hard")
        assertEquals(oct(3), SpacedRepetition.nextDue(topic.copy(difficulty = 3), listOf(oct(2)), start), "never less than a day")
    }

    @Test
    fun `the focus profile averages rated study per two-hour bucket and counts skipped plans`() {
        val study = 1L
        val records = listOf(
            RecordChunk(start = at(19, 9), end = at(19, 10), categoryId = study, focus = 4),
            RecordChunk(start = at(19, 9, 30), end = at(19, 10), categoryId = study, focus = 2),
            RecordChunk(start = at(18, 9), end = at(18, 10), categoryId = study, focus = 5),
            RecordChunk(start = at(19, 14), end = at(19, 15), categoryId = 2, focus = 1),
        )
        val plans = listOf(
            PlanBlock(start = at(19, 9), end = at(19, 10), categoryId = study),
            PlanBlock(start = at(19, 8), end = at(19, 9), categoryId = study),
            PlanBlock(start = at(21, 9), end = at(21, 10), categoryId = study),
        )
        val profile = FocusProfile.from(records, plans, setOf(study), at(20, 12), zone)
        val weekday = profile.buckets.getValue(FocusBucket(false, 8))
        assertEquals(3.0, weekday.meanFocus, "the 9:00 and 9:30 chunks on Monday")
        assertEquals(2, weekday.focusSamples)
        assertEquals(2, weekday.planned, "the block still to come isn't judged")
        assertEquals(0.5, weekday.skipRate, "9:00 was studied, 8:00 wasn't")
        assertEquals(5.0, profile.buckets.getValue(FocusBucket(true, 8)).meanFocus, "Sunday is the weekend")
        assertNull(profile.buckets[FocusBucket(false, 14)], "not a study category")
        assertEquals(0.0, profile.bonus(at(20, 9), zone), "2 samples are too few")
    }

    @Test
    fun `habit slots come after study, inside the habit's window, for the times still needed`() {
        val gym = Habit(id = 1, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), categoryId = 3, startDate = oct(5),
            suggestSlot = true, slotMinutes = 60, slotStart = LocalTime.of(17, 0), slotEnd = LocalTime.of(20, 0))
        val status = HabitProgress.evaluate(gym, listOf(HabitEntry(habitId = 1, date = oct(19), amount = 1)), emptyList(), emptyList(), emptyList(),
            { DayWindows.recordWindow(it, DaySettings(), zone) }, oct(5), oct(25), today)
        val prefs = StudyPrefs()
        val demands = Demands.from(today, prefs, emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), habits = listOf(status),
            plannedByHabit = mapOf(1L to 60))
        assertEquals(1, demands.size, "3 a week, 1 done and 1 planned")
        val demand = demands.single()
        assertEquals(DemandKind.HABIT, demand.kind)
        assertEquals("Gym: 1 of 3 this week", demand.reason)
        assertEquals(oct(25), demand.latest)
        val free = listOf(Interval(at(20, 13), at(20, 22)))
        val placed = StudyPlanner.suggest(StudyInput(at(20, 12), zone, free, demands, prefs.copy(dailyCapMinutes = 15)))
        assertEquals(listOf(at(20, 17) to at(20, 18)), placed.map { it.interval.start to it.interval.end }, "in its window, and study caps don't apply")
        assertEquals(3L, placed.single().demand.categoryId)

        val days = gym.copy(goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.MONDAY)))
        val dayStatus = HabitProgress.evaluate(days, emptyList(), emptyList(), emptyList(), emptyList(), { DayWindows.recordWindow(it, DaySettings(), zone) },
            oct(5), oct(25), today)
        val dayDemands = Demands.from(today, prefs, emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), habits = listOf(dayStatus))
        assertEquals(listOf(oct(20), oct(22)), dayDemands.map { it.earliest }, "this week's chosen days still to do")
        assertTrue(dayDemands.all { it.latest == it.earliest && it.window == TimeWindow(17 * 60, 20 * 60) })
        assertTrue(Demands.from(today, prefs, emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(),
            habits = listOf(dayStatus.copy(habit = days.copy(suggestSlot = false)))).isEmpty(), "only with Suggest a time on")
    }

    @Test
    fun `wrong preferences fall back to the defaults`() {
        val bad = StudyPrefs(longestMinutes = 5, shortestMinutes = 0, breakMinutes = -5, dailyCapMinutes = 0, weeklyCapMinutes = 5,
            preferred = listOf(TimeWindow(600, 500)), weeklyDaysOff = listOf(9, 6))
        val fixed = bad.sanitized()
        assertEquals(StudyPrefs().copy(weeklyDaysOff = listOf(9, 6)), fixed)
        assertEquals(setOf(DayOfWeek.SATURDAY), fixed.daysOff)
    }
}
