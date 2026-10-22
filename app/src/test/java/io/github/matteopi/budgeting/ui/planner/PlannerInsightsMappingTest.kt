package io.github.matteopi.budgeting.ui.planner

import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.insights.BlockLength
import io.github.matteopi.budgeting.core.planner.insights.CategoryTime
import io.github.matteopi.budgeting.core.planner.insights.DayPart
import io.github.matteopi.budgeting.core.planner.insights.DayTime
import io.github.matteopi.budgeting.core.planner.insights.DeadlineInsight
import io.github.matteopi.budgeting.core.planner.insights.FocusMean
import io.github.matteopi.budgeting.core.planner.insights.FocusSummary
import io.github.matteopi.budgeting.core.planner.insights.HabitInsight
import io.github.matteopi.budgeting.core.planner.insights.ModuleStudy
import io.github.matteopi.budgeting.core.planner.insights.OutcomeCounts
import io.github.matteopi.budgeting.core.planner.insights.Period
import io.github.matteopi.budgeting.core.planner.insights.PlanStats
import io.github.matteopi.budgeting.core.planner.insights.ReviewHabit
import io.github.matteopi.budgeting.core.planner.insights.TimeSummary
import io.github.matteopi.budgeting.core.planner.insights.Tip
import io.github.matteopi.budgeting.core.planner.insights.TipKind
import io.github.matteopi.budgeting.core.planner.insights.WeeklyReview
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.suggest.Demand
import io.github.matteopi.budgeting.core.planner.suggest.DemandKind
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import io.github.matteopi.budgeting.domain.planner.InsightsModel
import io.github.matteopi.budgeting.domain.planner.InsightsRange
import io.github.matteopi.budgeting.ui.planner.insights.PlannerInsightsMapping
import io.github.matteopi.budgeting.ui.planner.review.WeeklyReviewMapping
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Insights tab and the weekly review (docs/PLAN.md §13.9), in the week of Mon 19 Oct 2026. */
class PlannerInsightsMappingTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 25)
    private val week = Period(LocalDate.of(2026, 10, 19), today)
    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    private fun id(key: CategoryKey) = categories.single { it.systemKey == key }.id
    private val study = id(CategoryKey.STUDY)
    private val phone = id(CategoryKey.PHONE)

    private fun model(
        time: List<CategoryTime> = emptyList(),
        days: List<DayTime> = week.dates.map { DayTime(it, emptyMap()) },
        plans: PlanStats = PlanStats(OutcomeCounts(), emptyMap(), emptyMap(), emptyMap(), 0),
        focus: FocusSummary = FocusSummary(null, emptyMap(), emptyMap()),
        modules: List<ModuleStudy> = emptyList(),
        habits: List<HabitInsight> = emptyList(),
        deadlines: List<DeadlineInsight> = emptyList(),
        tips: List<Tip> = emptyList(),
    ) = InsightsModel(InsightsRange.WEEK, week, today, categories, TimeSummary(week, days, time), modules, plans, focus, habits, deadlines, tips)

    @Test
    fun `where time went names the top four categories in their own order, and the rest is everything else`() {
        val minutes = mapOf(phone to 600L, study to 900L, id(CategoryKey.SLEEP) to 300L, id(CategoryKey.TRAVEL) to 120L, id(CategoryKey.CHORES) to 60L)
        val days = week.dates.mapIndexed { i, d -> DayTime(d, if (i == 0) minutes else emptyMap()) }
        val time = listOf(
            CategoryTime(study, 900, 600), CategoryTime(phone, 600, 780), CategoryTime(id(CategoryKey.SLEEP), 300, 300),
            CategoryTime(id(CategoryKey.TRAVEL), 120, 0), CategoryTime(id(CategoryKey.CHORES), 60, 60),
        )
        val ui = PlannerInsightsMapping.ui(model(time = time, days = days))
        assertEquals("19–25 Oct", ui.title)
        assertEquals("33 h recorded", ui.totalText)
        val monday = ui.days.first()
        assertEquals("Mon", monday.label)
        assertEquals(listOf("Study", "Travel", "Phone and scrolling", "Sleep", "Everything else"), monday.parts.map { it.name }, "the categories' order")
        assertEquals(listOf(900L, 120L, 600L, 300L, 60L), monday.parts.map { it.minutes })
        assertNull(monday.parts.last().colorArgb)
        assertEquals("Mon 19 Oct: Study 15 h, Travel 2 h, Phone and scrolling 10 h, Sleep 5 h, Everything else 1 h", monday.description)
        assertEquals("Tue 20 Oct: nothing recorded", ui.days[1].description)
        assertEquals(
            listOf("Study" to "↑ 5 h", "Phone and scrolling" to "↓ 3 h", "Sleep" to "as before", "Travel" to "new", "Chores" to "as before"),
            ui.time.map { it.name to it.changeText },
        )
        assertEquals(listOf(true, false, null, true, null), ui.time.map { it.up })
    }

    @Test
    fun `study, planned against done and focus read as sentences and bars`() {
        val ec101 = Module(id = 1, code = "EC101", weeklyTargetMinutes = 300)
        val plans = PlanStats(
            all = OutcomeCounts(done = 7, partly = 2, skipped = 3),
            byPart = mapOf(DayPart.MORNING to OutcomeCounts(done = 5), DayPart.EVENING to OutcomeCounts(done = 1, skipped = 3)),
            byWeekday = mapOf(DayOfWeek.MONDAY to OutcomeCounts(done = 2, skipped = 1), DayOfWeek.FRIDAY to OutcomeCounts(skipped = 2)),
            byLength = mapOf(BlockLength.LONG to OutcomeCounts(skipped = 2, done = 1)),
            plannedMinutes = 600,
        )
        val focus = FocusSummary(FocusMean(3.5, 12), mapOf(18 to FocusMean(2.0, 4), 8 to FocusMean(4.25, 8)), mapOf(BlockLength.LONG to FocusMean(2.5, 3)))
        val ui = PlannerInsightsMapping.ui(model(plans = plans, focus = focus, modules = listOf(ModuleStudy(ec101, 180, 300), ModuleStudy(ec101.copy(id = 2, code = "MA102"), 60, null))))
        assertEquals(listOf("3 h of 5 h", "1 h"), ui.modules.map { it.text })
        assertEquals(listOf(0.6f, null), ui.modules.map { it.fraction })
        assertEquals("7 of 12 planned blocks done (58%)", ui.plannedText)
        assertEquals(
            listOf("2 partly · 3 skipped", "Evening blocks: 3 of 4 skipped", "Friday: 2 of 2 skipped", "Blocks of over 75 m: 2 of 3 skipped"),
            ui.plannedDetail,
        )
        assertEquals(listOf("08:00–10:00" to "4.3 · 8 ratings", "18:00–20:00" to "2.0 · 4 ratings"), ui.focus.map { it.label to it.text })
        assertEquals(0.85f, ui.focus.first().fraction)
        assertEquals(listOf("over 75 m"), ui.focusByLength.map { it.label })
        assertNull(PlannerInsightsMapping.ui(model()).plannedText)
        assertTrue(!PlannerInsightsMapping.ui(model()).hasRecord)
    }

    @Test
    fun `habits, deadlines and tips`() {
        val gym = Habit(id = 1, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), startDate = today)
        val run = Habit(id = 2, name = "Run", goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.MONDAY)), startDate = today)
        val habits = listOf(HabitInsight(gym, 0.75, 3, 5, null, 0), HabitInsight(run, null, 1, 1, DayOfWeek.MONDAY, 3))
        val essay = Task(id = 3, title = "EC101 essay", kind = TaskKind.DEADLINE, dueDate = today.plusDays(5))
        val deadlines = listOf(DeadlineInsight(essay, 120, 240, 5, 20.0, atRisk = true), DeadlineInsight(essay.copy(id = 4, title = "Form"), 0, null, 5, 0.0, false))
        val tip = Tip(TipKind.PHONE, "Phone and scrolling took 2 h a day in the last week.", "14 h in 7 days · 2 h a day")
        val ui = PlannerInsightsMapping.ui(model(habits = habits, deadlines = deadlines, tips = listOf(tip)))
        assertEquals(listOf("75% over 4 weeks", "Nothing to judge yet"), ui.habits.map { it.rateText })
        assertEquals(listOf("Streak 3 weeks · best 5", "Streak 1 day · best 1"), ui.habits.map { it.streakText })
        assertEquals("Most often missed on Mondays (3 times)", ui.habits[1].missedText)
        assertEquals("2 h done · 4 h left · Due Friday", ui.deadlines[0].text)
        assertEquals("At risk: 20 m a day over the last week won't finish it", ui.deadlines[0].riskText)
        assertEquals("0 m done · no estimate · Due Friday", ui.deadlines[1].text)
        assertNull(ui.deadlines[1].riskText)
        assertEquals(listOf("14 h in 7 days · 2 h a day"), ui.tips.map { it.numbers })
        assertEquals("October 2026", PlannerInsightsMapping.title(InsightsRange.MONTH, Period.month(today)))
    }

    @Test
    fun `the weekly review reads the week, then the planned days and their suggestions`() {
        val demand = Demand(key = "deadline:3", kind = DemandKind.DEADLINE, title = "EC101 essay", minutes = 120, latest = today.plusDays(4), reason = "")
        val start = LocalDate.of(2026, 10, 27).atTime(9, 0).atZone(zone).toInstant()
        val suggestion = Suggestion("deadline:3@1", Interval(start, start.plusSeconds(5400)), demand, "EC101 essay due Friday")
        val review = WeeklyReview(
            reviewed = week,
            planning = Period(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)),
            plannedMinutes = 150,
            studiedMinutes = 210,
            outcomes = OutcomeCounts(done = 1, skipped = 1),
            topCategories = listOf(CategoryTime(study, 210, 0), CategoryTime(phone, 120, 0)),
            habits = listOf(ReviewHabit(Habit(id = 1, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 2), startDate = today), 1.0, 2)),
            deadlines = emptyList(),
            nextWeek = Period(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)).dates.map { it to if (it.dayOfMonth == 27) listOf(suggestion) else emptyList() },
        )
        val ui = WeeklyReviewMapping.ui(review, categories, today, zone)
        assertEquals("19–25 Oct", ui.reviewedTitle)
        assertEquals("26 Oct – 1 Nov", ui.planningTitle)
        assertEquals("Next week", ui.planningLabel)
        assertEquals("Planned 2 h 30 m · studied 3 h 30 m", ui.studyText)
        assertEquals("1 of 2 planned blocks done, 1 skipped", ui.outcomesText)
        assertEquals(listOf("Study" to "3 h 30 m", "Phone and scrolling" to "2 h"), ui.top.map { it.name to it.text })
        assertEquals(listOf("100% this week · streak 2"), ui.habits.map { it.text })
        assertEquals(1, ui.suggestionCount)
        assertEquals(listOf("Tomorrow", "Tuesday 27 Oct"), ui.days.take(2).map { it.title })
        assertEquals(listOf("09:00–10:30"), ui.days[1].rows.map { it.timeText })
        assertEquals("Tue 27 Oct · 09:00–10:30", WeeklyReviewMapping.moveOption(suggestion.interval, today, zone).text)
        assertEquals("This week", WeeklyReviewMapping.ui(review.copy(planning = week), categories, today, zone).planningLabel)
    }
}
