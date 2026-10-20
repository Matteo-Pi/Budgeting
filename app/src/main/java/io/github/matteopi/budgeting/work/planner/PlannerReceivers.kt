package io.github.matteopi.budgeting.work.planner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.matteopi.budgeting.BudgetApp
import kotlinx.coroutines.launch

/** Runs [block] on the app scope while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.runAsync(context: Context, block: suspend (BudgetApp) -> Unit) {
    val app = context.applicationContext as BudgetApp
    val pending = goAsync()
    app.container.appScope.launch {
        try {
            block(app)
        } finally {
            pending.finish()
        }
    }
}

/** The Planner's reminder alarm going off (docs/PLAN.md §13.7). */
class PlannerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync(context) { it.container.plannerAlarms.fire() }
    }
}

/** After a restart, an app update, or a change of clock or time zone, the alarm is set again. */
class PlannerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> runAsync(context) { it.container.plannerAlarms.reschedule() }
        }
    }
}

/** A notification's buttons: *Done* ticks the task off and clears the notification; *Stop* stops the timer. */
class PlannerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TIMER_STOP) {
            runAsync(context) { it.container.plannerTimer.stop() }
            return
        }
        if (intent.action != ACTION_TASK_DONE) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        PlannerNotifications.cancel(context, notificationId)
        if (taskId < 0) return
        runAsync(context) { app ->
            val task = app.container.taskRepository.getTask(taskId)
            if (task != null && task.isOpen) app.container.taskRepository.complete(task)
        }
    }

    companion object {
        private const val ACTION_TASK_DONE = "io.github.matteopi.budgeting.PLANNER_TASK_DONE"
        private const val ACTION_TIMER_STOP = "io.github.matteopi.budgeting.PLANNER_TIMER_STOP"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun taskDoneIntent(context: Context, taskId: Long, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, PlannerActionReceiver::class.java).apply {
                action = ACTION_TASK_DONE
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun timerStopIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            PlannerNotifications.TIMER_NOTIFICATION_ID,
            Intent(context, PlannerActionReceiver::class.java).apply { action = ACTION_TIMER_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
