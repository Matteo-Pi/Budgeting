package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.insights.WeeklyReview
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.PlanOrigin
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.day.EditPlan
import io.github.matteopi.budgeting.data.settings.PlannerReminderSettings
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.plannerToday
import io.github.matteopi.budgeting.testing.seedPlannerSection
import io.github.matteopi.budgeting.testing.seedTask
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.ui.planner.insights.PlannerInsightsTags
import io.github.matteopi.budgeting.ui.planner.review.WeeklyReviewTags
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableMapping
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Planner's Insights tab and the weekly review (docs/PLAN.md §13.10, stage P6), with screenshots
 * `planner-insights-*`: three past weeks of the day record, plans, a habit and a deadline give every card; then the
 * weekly review plans the coming week with *Accept all*, and *Undo* takes it back.
 */
@RunWith(AndroidJUnit4::class)
class PlannerInsightsTest : UiTest() {

    @Before
    fun planner() {
        seedPlannerSection()
        blocking {
            appContainer.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = false, weeklyOn = false))
        }
    }

    @After
    fun noAlarmLeftBehind() {
        appContainer.plannerAlarms.cancel()
    }

    private val today get() = plannerToday()
    private val zone get() = appContainer.clock.zone()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant()

    private fun suggestedBlocks(): List<PlanBlock> = blocking {
        val now = Instant.ofEpochMilli(appContainer.clock.nowMillis())
        appContainer.dayRepository.getPlans(Interval(now.minus(1, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))).filter { it.origin == PlanOrigin.SUGGESTION }
    }

    /**
     * The three weeks before this one: on each weekday an hour of travel, a morning hour of EC101 rated 5, an evening
     * hour and a half rated 2 and two hours on the phone; an evening block planned on Mondays (done) and a Saturday
     * morning block (skipped); the gym on Tuesdays and Thursdays.
     */
    private fun seedThreeWeeks(): Long {
        val thisMonday = HabitProgress.weekStart(today)
        val categories = blocking { appContainer.dayRepository.observeCategories().first { it.isNotEmpty() } }
        fun id(key: CategoryKey) = categories.single { it.systemKey == key }.id
        val module = blocking { appContainer.moduleRepository.save(Module(code = "EC101", name = "Economics", weeklyTargetMinutes = 300))!! }
        val records = ArrayList<RecordChunk>()
        val plans = ArrayList<PlanBlock>()
        for (week in 1..3) {
            val monday = thisMonday.minusWeeks(week.toLong())
            for (d in 0L..4L) {
                val day = monday.plusDays(d)
                records += RecordChunk(start = at(day, 8), end = at(day, 9), categoryId = id(CategoryKey.TRAVEL))
                records += RecordChunk(start = at(day, 9), end = at(day, 10), categoryId = id(CategoryKey.STUDY), moduleId = module, focus = 5)
                records += RecordChunk(start = at(day, 19), end = at(day, 20, 30), categoryId = id(CategoryKey.STUDY), moduleId = module, focus = 2)
                records += RecordChunk(start = at(day, 21), end = at(day, 23), categoryId = id(CategoryKey.PHONE))
            }
            plans += PlanBlock(start = at(monday, 19), end = at(monday, 20, 30), categoryId = id(CategoryKey.STUDY), moduleId = module)
            plans += PlanBlock(start = at(monday.plusDays(5), 10), end = at(monday.plusDays(5), 11, 30), categoryId = id(CategoryKey.STUDY), moduleId = module)
        }
        blocking {
            appContainer.dayRepository.applyRecords(EditPlan(inserts = records))
            appContainer.dayRepository.applyPlans(EditPlan(inserts = plans))
            val gym = appContainer.habitRepository.save(
                Habit(name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 2), startDate = thisMonday.minusWeeks(3)),
            )
            for (week in 1..3) for (d in listOf(1L, 3L)) appContainer.habitRepository.setAmount(gym, thisMonday.minusWeeks(week.toLong()).plusDays(d), 1)
        }
        return module
    }

    @Test
    fun threeWeeksGiveEveryCardAndTheWeeklyReviewAcceptsAll() {
        val module = seedThreeWeeks()
        // The essay is due at the end of the week the review plans; the days before that week are off, so its study
        // all goes into that week.
        val (_, planning) = WeeklyReview.weeks(today)
        val essay = seedTask(
            Task(title = "EC101 essay", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.ASSIGNMENT, dueDate = planning.to, estimateMinutes = 600,
                moduleId = module),
        )
        blocking {
            val before = generateSequence(today) { it.plusDays(1) }.takeWhile { it.isBefore(planning.from) }.toSet()
            appContainer.settingsStore.setPlannerDaysOff(before, today)
        }

        launchMain()
        waitForExists(clickableText("Today"), "the Today tab", HEAVY_TIMEOUT_MS)
        goToTab("Insights")
        waitForExists(hasTestTag(PlannerInsightsTags.TIME), "where time went", HEAVY_TIMEOUT_MS)
        shot("planner-insights-01-this-week")

        // Last week: every card.
        val lastWeek = HabitProgress.weekStart(today).minusWeeks(1)
        tap(hasTestTag(PlannerInsightsTags.PREVIOUS), "the week before")
        waitForText(TimetableMapping.weekTitle(lastWeek))
        waitForExists(tagged(PlannerInsightsTags.TIME, "Phone and scrolling"), "phone in where time went")
        waitForExists(tagged(PlannerInsightsTags.TIME, "10 h"), "ten hours of phone")
        waitForExists(tagged(PlannerInsightsTags.TIME, "as before"), "the same as the week before")
        shot("planner-insights-02-last-week")
        waitForExists(tagged(PlannerInsightsTags.STUDY, "12 h 30 m of 5 h"), "EC101 against its target")
        waitForExists(tagged(PlannerInsightsTags.PLANNED, "1 of 2 planned blocks done (50%)"), "planned against done")
        waitForExists(tagged(PlannerInsightsTags.FOCUS, "08:00–10:00"), "focus in the morning")
        shot("planner-insights-03-study-and-focus")
        waitForExists(tagged(PlannerInsightsTags.HABITS, "Gym"), "the gym")
        waitForExists(tagged(PlannerInsightsTags.DEADLINES, "EC101 essay"), "the essay")
        waitForExists(tagged(PlannerInsightsTags.TIPS, "You focus best 08:00–10:00", substring = true), "the best hours tip")
        waitForExists(tagged(PlannerInsightsTags.TIPS, "You travel 3 h 45 m a week", substring = true), "the commute tip")
        shot("planner-insights-04-tips")

        // The weekly review: Accept all plans the week's suggestions in one go, and Undo takes them back.
        tapText("Weekly review")
        waitForText(if (today in planning) "This week" else "Next week")
        val acceptAll = hasTestTag(WeeklyReviewTags.ACCEPT_ALL) and isEnabled()
        waitForExists(acceptAll, "Accept all with suggestions", HEAVY_TIMEOUT_MS)
        shot("planner-insights-05-weekly-review")
        val label = firstText(hasTestTag(WeeklyReviewTags.ACCEPT_ALL)).orEmpty()
        val count = Regex("""\((\d+)\)""").find(label)?.groupValues?.get(1)?.toInt() ?: error("no count on '$label'")
        assertTrue("some study suggested", count > 0)
        tap(acceptAll, "Accept all")
        waitForText(if (count == 1) "Planned 1 block" else "Planned $count blocks")
        waitFor("the week to be planned") { suggestedBlocks().size == count }
        val blocks = suggestedBlocks()
        assertTrue("all in the planned week: $blocks", blocks.all { it.start.atZone(zone).toLocalDate() in planning })
        assertTrue("the essay's study among them", blocks.any { it.taskId == essay })
        shot("planner-insights-06-accepted")
        tapText("Undo")
        waitFor("the plans to be taken back") { suggestedBlocks().isEmpty() }
    }
}
