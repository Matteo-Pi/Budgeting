package io.github.matteopi.budgeting.data

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.reminders.ReminderPlan
import io.github.matteopi.budgeting.data.settings.PlannerReminderSettings
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.work.planner.PlannerNotifications
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Planner's single reminder alarm (docs/PLAN.md §13.7): it is set for the earliest coming reminder, follows the
 * default time setting, and posting due reminders marks them fired so each shows once.
 */
@RunWith(AndroidJUnit4::class)
class PlannerAlarmsTest {
    private val container get() = DataTestSupport.container
    private val alarms get() = container.plannerAlarms
    private val clock get() = container.clock

    @Before
    fun allowNotificationsAndTurnTheReviewOff() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${DataTestSupport.app.packageName} android.permission.POST_NOTIFICATIONS").close()
        // The end-of-day review reminds by default; these tests are about tasks until the last one.
        runBlocking { container.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = false)) }
    }

    @After
    fun noAlarmLeftBehind() {
        alarms.cancel()
        DataTestSupport.app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun at(date: LocalDate, time: LocalTime): Instant = ZonedDateTime.of(date, time, clock.zone()).toInstant()

    @Test
    fun theAlarmIsSetForTheEarliestComingReminder() = runBlocking<Unit> {
        val tomorrow = clock.today().plusDays(1)
        val later = container.taskRepository.save(Task(title = "Later", dueDate = tomorrow, dueTime = LocalTime.of(18, 0), reminderMinutesBefore = 60))
        container.taskRepository.save(Task(title = "Sooner", dueDate = tomorrow, reminderMinutesBefore = 0))
        alarms.reschedule()
        assertEquals(at(tomorrow, ReminderPlan.DEFAULT_TASK_TIME), alarms.scheduledAt, "09:00 for the untimed task comes first")

        container.settingsStore.setPlannerReminders(PlannerReminderSettings(taskMinute = 7 * 60, reviewOn = false))
        alarms.reschedule()
        assertEquals(at(tomorrow, LocalTime.of(7, 0)), alarms.scheduledAt, "the default time follows the setting")

        container.taskRepository.delete(container.taskRepository.observeOpenTasks().first().single { it.title == "Sooner" }.id)
        alarms.reschedule()
        assertEquals(at(tomorrow, LocalTime.of(17, 0)), alarms.scheduledAt)

        container.taskRepository.delete(later)
        alarms.reschedule()
        assertNull(alarms.scheduledAt, "no reminders, no alarm")
    }

    @Test
    fun firingPostsWhatIsDueOnceAndSetsTheNextAlarm() = runBlocking<Unit> {
        val now = Instant.ofEpochMilli(clock.nowMillis())
        // A reminder that came due a minute ago (its task is due in 4 minutes, reminded 5 before) and one tomorrow.
        val dueAt = now.plusSeconds(4 * 60).atZone(clock.zone())
        container.taskRepository.save(
            Task(title = "Hand in form", dueDate = dueAt.toLocalDate(), dueTime = dueAt.toLocalTime().withSecond(0).withNano(0), reminderMinutesBefore = 5),
        )
        container.taskRepository.save(Task(title = "Tomorrow", dueDate = clock.today().plusDays(1), dueTime = LocalTime.of(23, 0), reminderMinutesBefore = 0))
        container.settingsStore.setPlannerAlarmFired(now.minusSeconds(3_600))

        alarms.fire()

        val shown = DataTestSupport.app.getSystemService(NotificationManager::class.java).activeNotifications
        val titles = shown.map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
        assertTrue("Hand in form" in titles, "the due reminder is posted; have $titles")
        assertTrue("Tomorrow" !in titles)
        val fired = container.settingsStore.plannerAlarmFired.first()
        assertNotNull(fired)
        assertNotNull(alarms.scheduledAt, "the next one is set")
        assertTrue(alarms.scheduledAt!!.isAfter(fired))
        assertTrue(PlannerNotifications.canPost(DataTestSupport.app))
    }

    @Test
    fun theEndOfDayReviewRemindsUntilTheDayIsFinished() = runBlocking<Unit> {
        val today = clock.today()
        val now = Instant.ofEpochMilli(clock.nowMillis())
        // A review time a minute from now (or tomorrow's, so late in the day that a minute from now is tomorrow).
        val reviewAt = now.plusSeconds(60).atZone(clock.zone())
        val minute = reviewAt.hour * 60 + reviewAt.minute
        container.settingsStore.setPlannerReminders(PlannerReminderSettings(reviewOn = true, reviewMinute = minute))
        alarms.reschedule()
        assertEquals(reviewAt.withSecond(0).withNano(0).toInstant(), alarms.scheduledAt)

        container.dayRepository.finishDay(reviewAt.toLocalDate(), rating = 4, note = "")
        alarms.reschedule()
        if (reviewAt.toLocalDate() == today) {
            val next = assertNotNull(alarms.scheduledAt, "tomorrow's review is still to come")
            assertEquals(today.plusDays(1), next.atZone(clock.zone()).toLocalDate())
        } else {
            assertNull(alarms.scheduledAt, "today's review time has passed and tomorrow's day is finished")
        }
    }
}
