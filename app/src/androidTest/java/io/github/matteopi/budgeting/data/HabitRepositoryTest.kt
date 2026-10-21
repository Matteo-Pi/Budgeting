package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.day.DayEdits
import io.github.matteopi.budgeting.core.planner.habits.HabitDayState
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.reminders.ReminderKind
import io.github.matteopi.budgeting.data.settings.PlannerReminderSettings
import io.github.matteopi.budgeting.domain.planner.HabitsModel
import io.github.matteopi.budgeting.testing.DataTestSupport
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Habits against the real database (docs/PLAN.md §13.5): saving, a day's entry with Undo, the record counting, reminders. */
@RunWith(AndroidJUnit4::class)
class HabitRepositoryTest {
    private val container get() = DataTestSupport.container
    private val habits get() = container.habitRepository
    private val today get() = DataTestSupport.today()

    @Before
    fun noReviewReminder() = runBlocking<Unit> {
        container.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = false))
    }

    @After
    fun noAlarmLeftBehind() {
        container.plannerAlarms.cancel()
    }

    private suspend fun model(predicate: (HabitsModel) -> Boolean = { true }): HabitsModel =
        withTimeout(10_000) { container.habitService.observe().first(predicate) }

    @Test
    fun aHabitIsSavedLastAndArchivedNotDeleted() = runBlocking<Unit> {
        val gym = habits.save(Habit(name = " Gym ", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), startDate = today))
        val read = habits.save(Habit(name = "Read", goal = HabitGoal(GoalKind.AT_LEAST, 20, GoalUnit.MINUTES), startDate = today))
        assertEquals(listOf("Gym", "Read"), habits.getHabits().map { it.name }, "trimmed, in the order added")
        assertEquals(listOf(0, 1), habits.getHabits().map { it.sortOrder })
        habits.setArchived(gym, true)
        assertTrue(habits.getHabit(gym)!!.archived)
        assertEquals(listOf(read, gym), habits.getHabits().map { it.id }, "archived ones last")
    }

    @Test
    fun aDayIsTickedAddedToSkippedAndPutBack() = runBlocking<Unit> {
        val id = habits.save(Habit(name = "Read", goal = HabitGoal(GoalKind.AT_LEAST, 20, GoalUnit.MINUTES), startDate = today.minusDays(3)))
        val habit = habits.getHabit(id)!!
        assertNull(habits.markDone(habit, today), "nothing was there before")
        assertEquals(20, habits.getEntry(id, today)!!.amount, "a tick is the day's target")
        val before = habits.setAmount(id, today, 35)
        assertEquals(20, before!!.amount)
        habits.restore(id, today, before)
        assertEquals(20, habits.getEntry(id, today)!!.amount, "Undo puts the day back")
        assertEquals(1, habits.getEntries(today, today).size, "one entry per day")

        habits.setSkipped(id, today.minusDays(1), true)
        val status = assertNotNull(model { it.status(id)?.day(today)?.state == HabitDayState.DONE }.status(id))
        assertEquals(HabitDayState.SKIPPED, status.day(today.minusDays(1))!!.state)
        assertEquals(1, status.currentStreak, "the 3rd and 2nd days ago were missed; yesterday skipped; today done")

        habits.setAmount(id, today, 0)
        assertNull(habits.getEntry(id, today), "0 clears the day")
        val limit = habits.getHabit(habits.save(Habit(name = "Phone", goal = HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES), startDate = today)))!!
        assertNull(habits.markDone(limit, today))
        assertNull(habits.getEntry(limit.id, today), "a limit isn't ticked")
    }

    @Test
    fun aRecordCountedHabitFillsItselfInFromExerciseChunks() = runBlocking<Unit> {
        val exercise = withTimeout(10_000) { container.dayRepository.observeCategories().first { it.isNotEmpty() } }.single { it.systemKey == CategoryKey.EXERCISE }.id
        val yesterday = today.minusDays(1)
        val id = habits.save(
            Habit(name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), counting = HabitCounting.RECORD, categoryId = exercise, minMinutes = 30,
                startDate = yesterday),
        )
        val at = ZonedDateTime.of(yesterday.atTime(18, 0), container.clock.zone()).toInstant()
        container.dayRepository.applyRecords(DayEdits.place(emptyList(), RecordChunk(start = at, end = at.plusSeconds(45 * 60), categoryId = exercise))!!)
        val status = assertNotNull(model { it.status(id)?.day(yesterday)?.recorded == 1 }.status(id))
        assertEquals(HabitDayState.DONE, status.day(yesterday)!!.state)
        val sameWeek = HabitProgress.weekStart(yesterday) == HabitProgress.weekStart(today)
        assertEquals(if (sameWeek) 1 else 0, status.current.amount, "this week's count")
    }

    @Test
    fun aHabitWithAReminderSetsTheAlarmForTodayOrTomorrow() = runBlocking<Unit> {
        val id = habits.save(
            Habit(name = "Read", goal = HabitGoal(GoalKind.ON_DAYS, days = DayOfWeek.entries.toSet()), reminderTime = LocalTime.of(21, 30),
                startDate = today),
        )
        val reminders = container.plannerAlarms.reminders().filter { it.kind == ReminderKind.HABIT }
        assertTrue(reminders.isNotEmpty() && reminders.all { it.itemId == id && it.offersDone }, "have $reminders")
        val now = Instant.ofEpochMilli(container.clock.nowMillis())
        assertTrue(reminders.any { it.at.isAfter(now) }, "tomorrow's at least is still to come")
        container.plannerAlarms.reschedule()
        assertNotNull(container.plannerAlarms.scheduledAt)

        habits.markDone(habits.getHabit(id)!!, model().today)
        val after = container.plannerAlarms.reminders().filter { it.kind == ReminderKind.HABIT }
        assertTrue(after.none { it.at.atZone(container.clock.zone()).toLocalDate() == model().today }, "done today: no reminder for it")
    }
}
