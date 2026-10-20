package io.github.matteopi.budgeting.work.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.matteopi.budgeting.core.planner.reminders.Reminder
import io.github.matteopi.budgeting.core.planner.reminders.ReminderPlan
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Planner's single reminder alarm (docs/PLAN.md §13.7). [reschedule] sets one alarm for the earliest coming
 * reminder ([ReminderPlan.next]); when it goes off, [fire] posts what is due since the last post and sets the next.
 * No polling, and nothing to keep in step but one alarm.
 */
class PlannerAlarms(
    private val context: Context,
    private val taskRepository: TaskRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** The moment the alarm was last set for, or null when none is set; for tests and the settings screen. */
    @Volatile
    var scheduledAt: Instant? = null
        private set

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    /** False when Android refuses exact alarms, so reminders may come a few minutes late. */
    fun exactAlarmsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Every reminder the Planner has now. */
    suspend fun reminders(): List<Reminder> {
        val settings = settingsStore.plannerReminders.first()
        return ReminderPlan.taskReminders(taskRepository.getOpenTasks(), settings.taskTime, clock.zone(), clock.today())
    }

    suspend fun reschedule() = mutex.withLock { scheduleNext(Instant.ofEpochMilli(clock.nowMillis())) }

    /** Posts the reminders due between the last post and now, then sets the next alarm. */
    suspend fun fire() = mutex.withLock {
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val lastFired = settingsStore.plannerAlarmFired.first()
        for (reminder in ReminderPlan.dueBetween(reminders(), lastFired, now)) {
            PlannerNotifications.post(context, reminder)
        }
        settingsStore.setPlannerAlarmFired(now)
        scheduleNext(now)
    }

    /** Cancels the alarm (device tests leave none behind). */
    fun cancel() {
        alarmManager.cancel(pendingIntent())
        scheduledAt = null
    }

    private suspend fun scheduleNext(now: Instant) {
        val next = ReminderPlan.next(reminders(), now)
        if (next == null) {
            cancel()
            return
        }
        val at = next.at.toEpochMilli()
        try {
            if (exactAlarmsAllowed()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            }
            scheduledAt = next.at
        } catch (e: SecurityException) {
            // Exact alarms were refused between the check and the call: an inexact one still reminds.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            scheduledAt = next.at
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PlannerAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST_CODE = 7301
    }
}
