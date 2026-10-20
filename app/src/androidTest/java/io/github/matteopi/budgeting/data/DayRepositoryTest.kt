package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.day.DayEdits
import io.github.matteopi.budgeting.core.planner.day.DayMode
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RecordOrigin
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.data.settings.PlannerReminderSettings
import io.github.matteopi.budgeting.data.settings.TimerState
import io.github.matteopi.budgeting.domain.planner.TimerStop
import io.github.matteopi.budgeting.testing.DataTestSupport
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

/** The day record against the real database (docs/PLAN.md §13.5): edits with Undo, the timer, reviews and settings. */
@RunWith(AndroidJUnit4::class)
class DayRepositoryTest {
    private val container get() = DataTestSupport.container
    private val days get() = container.dayRepository
    private val zone get() = container.clock.zone()

    /** Yesterday, so every chunk is in the past whatever the time of day. */
    private val date get() = DataTestSupport.today().minusDays(1)
    private fun t(h: Int, m: Int = 0): Instant = ZonedDateTime.of(date.atTime(h, m), zone).toInstant()
    private val wholeDay get() = Interval(t(0), t(0).plusSeconds(2 * 86_400))

    @Before
    fun noReviewReminder() = runBlocking<Unit> {
        container.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = false))
    }

    @After
    fun noAlarmLeftBehind() {
        container.plannerAlarms.cancel()
        runBlocking { container.plannerTimer.stop() }
    }

    private suspend fun categories() = withTimeout(10_000) { days.observeCategories().first { it.isNotEmpty() } }

    private suspend fun category(key: CategoryKey) = categories().single { it.systemKey == key }.id

    @Test
    fun theCategoriesAreSeededOnFirstUseAndOnlyOnce() = runBlocking<Unit> {
        assertTrue(days.getCategories().isEmpty(), "nothing before the Planner's day record is used")
        assertEquals(DefaultTimeCategories.all.map { it.name }, categories().map { it.name })
        days.ensureCategoriesSeeded()
        assertEquals(DefaultTimeCategories.all.size, days.getCategories().size)
        val study = categories().single { it.systemKey == CategoryKey.STUDY }
        days.saveCategory(study.copy(name = "Revision"))
        assertEquals("Revision", categories().single { it.systemKey == CategoryKey.STUDY }.name, "a renamed default keeps its meaning")
    }

    @Test
    fun anEditIsOneTransactionAndUndoGivesTheRowsBack() = runBlocking<Unit> {
        val study = category(CategoryKey.STUDY)
        val social = category(CategoryKey.SOCIAL)
        val first = days.applyRecords(DayEdits.place(emptyList(), RecordChunk(start = t(9), end = t(12), categoryId = study))!!)
        val morning = first.inserts.single()
        assertTrue(morning.id > 0)
        assertTrue(morning.createdAt.isAfter(Instant.EPOCH), "new rows are stamped")
        val before = days.getRecords(wholeDay)

        // Lunch in the middle splits the morning in two.
        val lunch = days.applyRecords(DayEdits.place(before, RecordChunk(start = t(10), end = t(11), categoryId = social))!!)
        assertEquals(listOf(t(9) to t(10), t(10) to t(11), t(11) to t(12)), days.getRecords(wholeDay).map { it.start to it.end })

        days.applyRecords(lunch.inverse())
        assertEquals(before, days.getRecords(wholeDay), "Undo gives the rows back exactly")
        assertEquals(mapOf<Long, Int>(), days.observeMinutesByTask().first())
    }

    @Test
    fun theTimerPlacesItsChunkOverWhatItOverlapsAndAShortRunRecordsNothing() = runBlocking<Unit> {
        val study = category(CategoryKey.STUDY)
        val rest = category(CategoryKey.REST)
        val task = container.taskRepository.save(Task(title = "Problem set"))
        val now = Instant.ofEpochMilli(container.clock.nowMillis())
        // Rest recorded over the last two hours; a timer started 30 minutes ago takes its end.
        days.applyRecords(DayEdits.place(emptyList(), RecordChunk(start = now.minusSeconds(7_200), end = now, categoryId = rest))!!)
        container.settingsStore.setPlannerTimer(TimerState(now.minusSeconds(1_800).toEpochMilli(), study, taskId = task))

        val stopped = assertIs<TimerStop.Recorded>(container.plannerTimer.stop())
        assertEquals(RecordOrigin.TIMER, stopped.chunk.origin)
        val records = days.getRecords(Interval(now.minusSeconds(10_000), now.plusSeconds(60)))
        assertEquals(listOf(rest, study), records.map { it.categoryId })
        assertEquals(records[1].start, records[0].end, "the rest now ends where the timer began")
        assertNull(container.settingsStore.plannerTimer.first())
        assertEquals(30, days.observeMinutesByTask().first()[task] ?: 0, "the timer's time counts against the task")

        container.plannerTimer.start(study)
        assertIs<TimerStop.TooShort>(container.plannerTimer.stop())
        assertEquals(2, days.getRecords(Interval(now.minusSeconds(10_000), now.plusSeconds(600))).size)
    }

    @Test
    fun changingTheDaySettingLosesNothing() = runBlocking<Unit> {
        val study = category(CategoryKey.STUDY)
        // A late night: 23:00 to 01:30.
        days.applyRecords(DayEdits.place(emptyList(), RecordChunk(start = t(23), end = t(23).plusSeconds(9_000), categoryId = study))!!)
        val wakeSleep = DaySettings(sleepMinutes = List(7) { 2 * 60 })
        container.settingsStore.setPlannerDay(wakeSleep)
        val inWakeSleep = container.plannerService.dayFlow(date).first()
        assertEquals(1, inWakeSleep.records.size, "wake to 02:00 holds the whole night")

        container.settingsStore.setPlannerDay(DaySettings(mode = DayMode.FIXED, cutoffMinute = 60))
        val fixed = container.plannerService.dayFlow(date).first()
        assertEquals(DayWindows.recordWindow(date, DaySettings(mode = DayMode.FIXED, cutoffMinute = 60), zone), fixed.window)
        assertEquals(1, fixed.records.size, "the chunk is still there, shown across the 01:00 cut-off")
        assertEquals(1, container.plannerService.dayFlow(date.plusDays(1)).first().records.size, "and on the next day too")
        assertEquals(1, days.getRecords(wholeDay).size, "nothing was moved or split")
    }

    @Test
    fun aDayIsFinishedWithARatingAndCanBeReopened() = runBlocking<Unit> {
        days.finishDay(date, rating = 4, note = "Good day")
        val review = assertNotNull(days.getReviews(date, date).singleOrNull())
        assertNotNull(review.finishedAt)
        assertEquals(4, review.rating)
        assertEquals("Good day", review.note)
        assertTrue(container.plannerService.dayFlow(date).first().finished)

        days.reopenDay(date)
        val reopened = days.getReviews(date, date).single()
        assertNull(reopened.finishedAt)
        assertEquals(4, reopened.rating, "the rating stays")
    }
}
