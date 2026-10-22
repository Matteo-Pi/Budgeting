package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.PlanOrigin
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import io.github.matteopi.budgeting.data.settings.PlannerReminderSettings
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.plannerToday
import io.github.matteopi.budgeting.testing.seedModule
import io.github.matteopi.budgeting.testing.seedPlannerSection
import io.github.matteopi.budgeting.testing.seedTask
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.ui.planner.day.DayMapping
import io.github.matteopi.budgeting.ui.planner.day.DayTags
import io.github.matteopi.budgeting.ui.planner.settings.StudySettingsTags
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionTags
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionsMapping
import io.github.matteopi.budgeting.ui.planner.today.TodayTags
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Suggested study (docs/PLAN.md §13.10, stage P5), with screenshots `suggest-*`: an essay's suggestion accepted onto
 * Today and into the day editor, with *Undo*; *Another time* offering the time after it; and a day off clearing the
 * day. The waking day is set to run from this hour to the same hour tomorrow, so Today has free time left whenever
 * the test runs.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionsTest : UiTest() {

    @Before
    fun planner() {
        seedPlannerSection()
        val hour = Instant.ofEpochMilli(appContainer.clock.nowMillis()).atZone(appContainer.clock.zone()).hour * 60
        blocking {
            appContainer.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = false))
            appContainer.settingsStore.setPlannerDay(DaySettings(wakeMinutes = List(7) { hour }, sleepMinutes = List(7) { hour }))
        }
    }

    @After
    fun noAlarmLeftBehind() {
        appContainer.plannerAlarms.cancel()
    }

    private val today get() = plannerToday()

    /** The EC101 essay, due in four days with 2 hours of work: a 90-minute and a 30-minute block. */
    private fun seedEssay(): Long {
        val module = seedModule("EC101", "Economics")
        return seedTask(
            Task(title = "EC101 essay", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.ASSIGNMENT, dueDate = today.plusDays(4),
                estimateMinutes = 120, moduleId = module),
        )
    }

    /** What the planner suggests now, worked out as the screens do. */
    private fun suggestions(): List<Suggestion> = blocking { withTimeout(HEAVY_TIMEOUT_MS) { appContainer.suggestionService.observe().first() } }.suggestions

    private fun suggestedBlocks(): List<PlanBlock> = blocking {
        val now = Instant.ofEpochMilli(appContainer.clock.nowMillis())
        appContainer.dayRepository.getPlans(Interval(now.minus(1, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))).filter { it.origin == PlanOrigin.SUGGESTION }
    }

    private fun description(suggestion: Suggestion) = SuggestionsMapping.row(suggestion, today, appContainer.clock.zone()).description

    /**
     * Waits for Today to show the planner's first suggestion and returns it. Read again on each try: "now" moves in
     * quarter hours, and one may pass between the test's run and the screen's.
     */
    private fun firstShown(): Suggestion {
        var first: Suggestion? = null
        waitFor("Today to show the first suggestion", HEAVY_TIMEOUT_MS) {
            first = suggestions().first()
            exists(hasContentDescription(description(first!!)))
        }
        return first!!
    }

    private fun dismissed(): Set<String> = blocking { appContainer.suggestionRepository.observeDismissed().first() }

    private fun inRow(text: String): SemanticsMatcher = hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(SuggestionTags.ROW))

    private fun openToday() {
        launchMain()
        waitForExists(clickableText("Today"), "the Today tab", HEAVY_TIMEOUT_MS)
        waitForExists(hasTestTag(TodayTags.NOW), "the Now card")
    }

    @Test
    fun anAcceptedSuggestionIsPlannedOnTodayAndInTheDayEditor() {
        val essay = seedEssay()
        openToday()
        waitForText("Suggested study", timeoutMs = HEAVY_TIMEOUT_MS)
        val first = firstShown()
        assertEquals("EC101 essay", first.demand.title)
        waitForText("EC101 essay due", substring = true)
        shot("suggest-01-today")

        tap(inRow("Accept"), "Accept on the first suggestion")
        waitForText("Planned: EC101 essay at", substring = true)
        waitFor("the suggestion to become a plan") { suggestedBlocks().size == 1 }
        val block = suggestedBlocks().single()
        // 90 minutes, the longest block, unless midnight (a day's cap is per date) comes first.
        assertTrue("${Interval(block.start, block.end)}", Interval(block.start, block.end).minutes in 30L..90L)
        assertEquals(essay, block.taskId)
        assertEquals("EC101 essay", block.note)
        waitForExists(tagged(TodayTags.ON_TODAY, "EC101 essay"), "the planned block in the day's agenda")
        waitUntilGone(hasContentDescription(description(first)), "the accepted suggestion")
        shot("suggest-02-accepted")

        // Undo takes the plan away and the suggestion comes back; accepted again, it is in the day editor.
        tapText("Undo")
        waitFor("the plan to be taken back") { suggestedBlocks().isEmpty() }
        firstShown()
        tap(inRow("Accept"), "Accept on the first suggestion")
        waitFor("the plan again") { suggestedBlocks().size == 1 }
        val again = suggestedBlocks().single()
        tapText("Edit day")
        waitForText("Day record")
        // The day editor names a plan by its category and module (the note is its detail), with its time after.
        val time = DayMapping.range(again.start, again.end, appContainer.clock.zone())
        waitForExists(tagged(DayTags.SEGMENT, "Study · EC101") and tagged(DayTags.SEGMENT, time, substring = true), "the planned block, $time, in the day editor")
        shot("suggest-03-day-editor")
    }

    @Test
    fun anotherTimeOffersTheTimeAfterAndUndoBringsItBack() {
        seedEssay()
        openToday()
        val first = firstShown()

        tap(inRow("Another time"), "Another time on the first suggestion")
        waitForText("Suggesting another time")
        waitFor("the time to be dismissed") { dismissed().size == 1 }
        // The key is the demand and the start, and the whole dismissed block is passed over.
        val key = dismissed().single()
        assertTrue(key, key.startsWith("${first.demand.key}@"))
        val dismissedStart = Instant.ofEpochMilli(key.substringAfter('@').toLong())
        val moved = firstShown()
        assertEquals(first.demand.key, moved.demand.key)
        assertTrue("offered after the dismissed time, not ${moved.interval}", moved.interval.start.isAfter(dismissedStart))
        if (key == first.key) assertTrue("offered after ${first.interval}, not ${moved.interval}", !moved.interval.start.isBefore(first.interval.end))
        shot("suggest-04-another-time")

        tapText("Undo")
        waitFor("the dismissal to be taken back") { dismissed().isEmpty() }
        assertTrue("the earlier time is offered again", firstShown().interval.start.isBefore(moved.interval.start))
    }

    @Test
    fun aDayOffClearsTheDay() {
        seedEssay()
        openToday()
        waitForExists(hasTestTag(SuggestionTags.ROW), "a suggestion on Today", HEAVY_TIMEOUT_MS)

        // Today's waking day runs into tomorrow, so both days are taken off.
        tapIcon("Planner settings")
        waitForText("Planner settings")
        tapText("Suggested study")
        tap(hasTestTag(StudySettingsTags.DAY_OFF_TODAY), "Day off today")
        tap(hasText("Day off tomorrow") and isToggleable(), "Day off tomorrow")
        waitFor("both days to be off") { blocking { appContainer.settingsStore.plannerDaysOff.first() } == setOf(today, today.plusDays(1)) }
        shot("suggest-05-days-off")

        tapBack()
        tapBack()
        waitForExists(hasTestTag(TodayTags.NOW), "the Now card")
        waitUntilGone(hasTestTag(SuggestionTags.ROW), "today's suggestions")
        // The suggestions are the end of the list here (no tasks or habits today), so it is laid out to the end.
        swipeListUp(3)
        assertNeverShows(hasTestTag(SuggestionTags.ROW) or txt("Suggested study"), "a suggestion on Today")
        val left = suggestions()
        val zone = appContainer.clock.zone()
        assertTrue("the essay is still suggested, after the days off", left.isNotEmpty())
        assertTrue(
            "nothing on a day off: ${left.map { it.interval }}",
            left.all { it.interval.start.atZone(zone).toLocalDate().isAfter(today.plusDays(1)) },
        )
        shot("suggest-06-day-off-today")
    }
}
