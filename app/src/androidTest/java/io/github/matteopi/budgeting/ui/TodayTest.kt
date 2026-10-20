package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
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
import io.github.matteopi.budgeting.ui.planner.today.TodayTags
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Today tab (docs/PLAN.md §13.10, stage P3), with screenshots `today-*`: the Now card and the day's agenda, the
 * timer started and stopped into the record with *Undo*, and *Edit day* opening the day record.
 */
@RunWith(AndroidJUnit4::class)
class TodayTest : UiTest() {

    @After
    fun cleanUp() {
        appContainer.plannerAlarms.cancel()
        blocking { appContainer.plannerTimer.stop() }
    }

    private fun timerRecords() = blocking {
        val now = Instant.ofEpochMilli(appContainer.clock.nowMillis())
        appContainer.dayRepository.getRecords(Interval(now.minusSeconds(86_400), now.plusSeconds(3_600))).filter { it.origin == RecordOrigin.TIMER }
    }

    @Test
    fun theTimerRecordsIntoTheDayAndUndoTakesItBack() {
        seedPlannerSection()
        blocking { appContainer.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = false)) }
        // A class on today's weekday, in the agenda whatever the time.
        seedEntry(TimetableEntry(title = "EC101 Lecture", room = "LT1", weekday = plannerToday().dayOfWeek, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)))
        launchMain()
        waitForExists(clickableText("Today"), "the Today tab", HEAVY_TIMEOUT_MS)
        waitForExists(hasTestTag(TodayTags.NOW), "the Now card")
        waitForExists(tagged(TodayTags.ON_TODAY, "EC101 Lecture"), "the lecture in the day's agenda")
        shot("today-01-agenda")

        tapIcon("Start timer")
        waitForText("Start a timer")
        tapText("Start")
        waitFor("the timer to run") { blocking { appContainer.plannerTimer.state.first() } != null }
        val study = blocking { appContainer.dayRepository.observeCategories().first { it.isNotEmpty() } }.single { it.systemKey == CategoryKey.STUDY }
        assertEquals(study.id, blocking { appContainer.plannerTimer.state.first() }!!.categoryId)
        waitForText("Recording: Study", substring = true)
        shot("today-02-timer-running")

        // As if it had run for 20 minutes.
        blocking {
            val state = appContainer.plannerTimer.state.first()!!
            appContainer.settingsStore.setPlannerTimer(state.copy(startMillis = appContainer.clock.nowMillis() - 20 * 60_000))
        }
        tapIcon("Stop timer")
        waitForText("Recorded 20 m of Study", substring = true)
        assertNull(blocking { appContainer.plannerTimer.state.first() })
        val recorded = timerRecords().single()
        assertEquals(study.id, recorded.categoryId)
        assertTrue(recorded.end.toEpochMilli() - recorded.start.toEpochMilli() >= 20 * 60_000)
        shot("today-03-recorded")

        tapText("Undo")
        waitFor("the timer's chunk to go") { timerRecords().isEmpty() }

        tapText("Edit day")
        waitForText("Day record")
        assertNotNull(compose.onAllNodes(hasTestTag("day-segment")).fetchSemanticsNodes().firstOrNull())
        shot("today-04-day-record")
    }
}
