package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.day.EditPlan
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RecordOrigin
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.data.settings.PlannerReminderSettings
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.plannerToday
import io.github.matteopi.budgeting.testing.seedEntry
import io.github.matteopi.budgeting.testing.seedPlannerSection
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.planner.day.DayTags
import io.github.matteopi.budgeting.ui.planner.today.TodayTags
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The day record on screen (docs/PLAN.md §13.10, stage P3), with screenshots `day-*`, on yesterday so the time of day
 * never matters: *Finish yesterday* from Today, a class confirmed, a stretch typed in exactly, a boundary moved by its
 * accessibility action, a plan confirmed with *Confirm all*, and *Finish day* with a rating.
 */
@RunWith(AndroidJUnit4::class)
class DayRecordTest : UiTest() {

    @After
    fun noAlarmLeftBehind() {
        appContainer.plannerAlarms.cancel()
    }

    private val yesterday get() = plannerToday().minusDays(1)
    private fun t(h: Int, m: Int = 0): Instant = ZonedDateTime.of(yesterday.atTime(h, m), appContainer.clock.zone()).toInstant()
    private fun records(): List<RecordChunk> = blocking { appContainer.dayRepository.getRecords(Interval(t(0), t(0).plusSeconds(86_400))) }

    private fun segment(title: String) = tagged(DayTags.SEGMENT, title)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theReviewFillsInConfirmsAndFinishesYesterday() {
        seedPlannerSection()
        blocking { appContainer.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = false)) }
        seedEntry(TimetableEntry(title = "EC101 Lecture", room = "LT1", weekday = yesterday.dayOfWeek, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)))
        val categories = blocking { appContainer.dayRepository.observeCategories().first { it.isNotEmpty() } }
        val study = categories.single { it.systemKey == CategoryKey.STUDY }.id
        val social = categories.single { it.systemKey == CategoryKey.SOCIAL }.id
        val plan = blocking {
            appContainer.dayRepository.applyPlans(EditPlan(inserts = listOf(PlanBlock(start = t(14), end = t(15, 30), categoryId = study)))).inserts.single()
        }

        launchMain()
        waitForExists(clickableText("Today"), "the Today tab", HEAVY_TIMEOUT_MS)
        waitForExists(hasTestTag(TodayTags.FINISH_YESTERDAY), "Finish yesterday on Today")
        shot("day-01-today-finish-yesterday")
        tapText("Finish yesterday")

        waitForText("End-of-day review")
        waitForExists(tagged(DayTags.BANNER, "3 not recorded · 2 to confirm", substring = true), "the review's counts")
        shot("day-02-review")

        // The class happened as planned.
        tap(segment("EC101 Lecture"), "the lecture")
        tapText("Happened as planned")
        waitFor("the lecture to be recorded") { records().any { it.sourceKey?.startsWith("entry:") == true && it.start == t(9) && it.end == t(10) } }
        waitForExists(tagged(DayTags.BANNER, "1 to confirm", substring = true), "one left to confirm")

        // Lunch with friends, typed exactly: 12:00 to 13:10.
        tap(segment("Not recorded"), "a stretch not recorded")
        waitForExists(hasTestTag(DayTags.SAVE), "the chunk sheet")
        typeInTag(DayTags.START, "1200")
        typeInTag(DayTags.END, "1310")
        tapText("Social")
        typeInTag(DayTags.NOTE, "Lunch")
        shot("day-03-sheet")
        tap(hasTestTag(DayTags.SAVE), "Save")
        waitFor("lunch to be recorded") { records().any { it.categoryId == social && it.start == t(12) && it.end == t(13, 10) && it.note == "Lunch" } }

        // Its end moves 15 minutes later with the boundary's accessibility action, as TalkBack would.
        val boundary = hasTestTag(DayTags.BOUNDARY) and hasContentDescription("Boundary at 13:10")
        waitForExists(boundary, "lunch's end")
        scrollIntoView(boundary)
        compose.onNode(boundary).performCustomAccessibilityActionWithLabel("Move later by 15 minutes")
        waitFor("lunch to end at 13:30") { records().any { it.categoryId == social && it.end == t(13, 30) } }
        shot("day-04-moved")

        // The study block was planned: Confirm all records it as planned.
        tapText("Confirm all as planned")
        waitFor("the plan to be confirmed") { records().any { it.planBlockId == plan.id && it.origin == RecordOrigin.CONFIRMED } }
        val confirmed = records().single { it.planBlockId == plan.id }
        assertEquals(study, confirmed.categoryId)
        assertEquals(t(14) to t(15, 30), confirmed.start to confirmed.end)

        // Finish the day, rated 4.
        tapText("Finish day")
        waitForText("Finish the day")
        tap(clickableText("☆") and hasAnyAncestor(isDialog()), "the 4th star", index = 3)
        shot("day-05-finish")
        tapInDialog("Finish")
        waitFor("the day to be finished") {
            blocking { appContainer.dayRepository.getReviews(yesterday, yesterday) }.singleOrNull()?.finishedAt != null
        }
        val review = blocking { appContainer.dayRepository.getReviews(yesterday, yesterday) }.single()
        assertEquals(4, review.rating)
        assertNotNull(review.finishedAt)
        waitForText("Finished")
        assertTrue(records().none { it.origin == RecordOrigin.TIMER })
    }
}
