package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.day.DayEdits
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.data.settings.PlannerReminderSettings
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.plannerToday
import io.github.matteopi.budgeting.testing.seedPlannerSection
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.planner.habits.HabitTags
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Habits tab (docs/PLAN.md §13.10, stage P4), with screenshots `habits-*`: gym 3 times a week ticked onto a
 * streak, with *Undo*; an exercise chunk in the day record filling in a record-counted habit; and a limit added in the
 * editor going over.
 */
@RunWith(AndroidJUnit4::class)
class HabitsTest : UiTest() {

    @Before
    fun planner() {
        seedPlannerSection()
        blocking { appContainer.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = false)) }
    }

    @After
    fun noAlarmLeftBehind() {
        appContainer.plannerAlarms.cancel()
    }

    private val today get() = plannerToday()

    private fun seedHabit(habit: Habit): Long = blocking { appContainer.habitRepository.save(habit) }

    private fun entry(habitId: Long, date: LocalDate) = blocking { appContainer.habitRepository.getEntry(habitId, date) }

    private fun openHabits() {
        launchMain()
        waitForExists(clickableText("Today"), "the Today tab", HEAVY_TIMEOUT_MS)
        goToTab("Habits")
    }

    @Test
    fun gymThreeTimesAWeekIsTickedOntoItsStreak() {
        // The two weeks before this one were done: Monday, Wednesday and Friday each.
        val monday = HabitProgress.weekStart(today)
        val id = seedHabit(Habit(name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), startDate = monday.minusDays(14)))
        blocking {
            for (week in listOf(14L, 7L)) for (day in listOf(0L, 2L, 4L)) appContainer.habitRepository.setAmount(id, monday.minusDays(week - day), 1)
        }
        openHabits()
        waitForExists(tagged(HabitTags.ROW, "Gym"), "the Gym habit")
        waitForText("3 times a week · 0 of 3 this week", substring = true)
        waitForText("Streak 2 weeks · best 2")
        shot("habits-01-tab")

        tap(hasContentDescription("Done today: Gym"), "Gym's tick")
        waitForText("1 of 3 this week", substring = true)
        assertEquals(1, entry(id, today)!!.amount)
        shot("habits-02-ticked")
        tapText("Undo")
        waitFor("the tick to be taken back") { entry(id, today) == null }
        tap(hasContentDescription("Done today: Gym"), "Gym's tick")
        waitFor("the tick again") { entry(id, today)?.amount == 1 }

        // The habit's page: this month, the rates and the weeks.
        tap(tagged(HabitTags.ROW, "Gym"), "the Gym habit")
        waitForText("Recent weeks")
        waitForText("Streak 2 weeks · best 2")
        waitForText("1 of 3 this week")
        assertTrue(exists(hasTestTag(HabitTags.CELL) and hasContentDescription("Done", substring = true)))
        shot("habits-03-page")
    }

    @Test
    fun anExerciseChunkInTheDayRecordFillsInARecordCountedHabit() {
        val exercise = blocking { appContainer.dayRepository.observeCategories().first { it.isNotEmpty() } }.single { it.systemKey == CategoryKey.EXERCISE }.id
        val yesterday = today.minusDays(1)
        val id = seedHabit(
            Habit(name = "Run", goal = HabitGoal(GoalKind.ON_DAYS, days = DayOfWeek.entries.toSet()), counting = HabitCounting.RECORD,
                categoryId = exercise, minMinutes = 30, startDate = yesterday),
        )
        openHabits()
        waitForExists(tagged(HabitTags.ROW, "Run"), "the Run habit")
        assertTrue("A habit counted from the record has no tick", !exists(hasContentDescription("Done today: Run")))
        waitForText("Streak 0 days · best 0")

        // 45 minutes of exercise yesterday evening, in the day record.
        val start = ZonedDateTime.of(yesterday.atTime(18, 0), appContainer.clock.zone()).toInstant()
        blocking {
            appContainer.dayRepository.applyRecords(DayEdits.place(emptyList(), RecordChunk(start = start, end = start.plusSeconds(45 * 60), categoryId = exercise))!!)
        }
        waitForText("Streak 1 day · best 1")
        shot("habits-04-from-the-record")
        assertNull("nothing was entered by hand", entry(id, yesterday))

        tap(tagged(HabitTags.ROW, "Run"), "the Run habit")
        waitForText("Counted from Exercise in the day record (sessions of 30 m or more)")
        shot("habits-05-record-page")
    }

    @Test
    fun aLimitAddedInTheEditorGoesOver() {
        openHabits()
        waitForText("No habits yet", substring = true)
        tapIcon("Add habit")
        waitForText("Add habit")
        typeInTag(HabitTags.NAME, "Phone and scrolling")
        tapText("At most (a limit)")
        shot("habits-06-editor")
        tap(hasTestTag(HabitTags.SAVE), "Save")
        waitForExists(tagged(HabitTags.ROW, "Phone and scrolling"), "the new habit")
        val habit = blocking { appContainer.habitRepository.getHabits() }.single()
        assertEquals(HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES), habit.goal)
        assertEquals(today, habit.startDate)

        tap(hasTestTag(HabitTags.ADD), "Add")
        typeInTag(HabitTags.AMOUNT, "45")
        tapInDialog("Add")
        waitForText("45 m of 1 h today", substring = true)
        tap(hasTestTag(HabitTags.ADD), "Add")
        typeInTag(HabitTags.AMOUNT, "30")
        tapInDialog("Add")
        waitForText("1 h 15 m of 1 h today · over the limit", substring = true)
        assertEquals(75, entry(habit.id, today)!!.amount)
        shot("habits-07-over")

        // Skip today takes the day out.
        tapIcon("More for Phone and scrolling")
        tapText("Skip today")
        waitForText("Skipped today", substring = true)
        assertTrue(entry(habit.id, today)!!.skipped)
    }
}
