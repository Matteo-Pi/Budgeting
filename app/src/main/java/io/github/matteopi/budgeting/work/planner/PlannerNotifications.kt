package io.github.matteopi.budgeting.work.planner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.R
import io.github.matteopi.budgeting.core.planner.reminders.Reminder
import io.github.matteopi.budgeting.core.planner.reminders.ReminderKind

/** The Planner's notification channels and posting (docs/PLAN.md §13.7). */
object PlannerNotifications {
    const val REMINDERS_CHANNEL_ID = "planner_reminders"
    const val REVIEWS_CHANNEL_ID = "planner_reviews"
    const val TIMER_CHANNEL_ID = "planner_timer"

    /**
     * `MainActivity` opens the Planner on this action: [EXTRA_TASK_ID] picks a task, [EXTRA_REVIEW_DATE] (an epoch
     * day) opens that day's review, and [EXTRA_TODAY] the Today tab.
     */
    const val ACTION_OPEN_PLANNER = "io.github.matteopi.budgeting.OPEN_PLANNER"
    const val EXTRA_TASK_ID = "planner_task_id"
    const val EXTRA_REVIEW_DATE = "planner_review_date"
    const val EXTRA_TODAY = "planner_today"

    /** The running timer's notification, one at a time. */
    const val TIMER_NOTIFICATION_ID = 7302

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(REMINDERS_CHANNEL_ID, "Planner reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tasks and deadlines you asked to be reminded of."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(REVIEWS_CHANNEL_ID, "Planner reviews", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The end-of-day review, at the time you chose."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(TIMER_CHANNEL_ID, "Planner timer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while a timer is recording."
                setShowBadge(false)
            },
        )
    }

    private fun openIntent(context: Context, extras: Intent.() -> Unit) = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_PLANNER
        extras()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    /** The ongoing timer notification: what it records, a running clock from [startMillis], and *Stop*. */
    fun showTimer(context: Context, title: String, startMillis: Long) {
        if (!canPost(context)) return
        val open = openIntent(context) { putExtra(EXTRA_TODAY, true) }
        val notification = NotificationCompat.Builder(context, TIMER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recording: $title")
            .setContentText("Tap Stop when you finish; it goes into today's record.")
            .setWhen(startMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(PendingIntent.getActivity(context, TIMER_NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, "Stop", PlannerActionReceiver.timerStopIntent(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TIMER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // The permission was taken away between the check and posting: the Today card still shows the timer.
        }
    }

    fun cancelTimer(context: Context) = cancel(context, TIMER_NOTIFICATION_ID)

    /** True when the app may post notifications (always before Android 13). */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The notification id of [reminder]: the same reminder always replaces its own notification. */
    fun idOf(reminder: Reminder): Int = reminder.key.hashCode()

    fun post(context: Context, reminder: Reminder) {
        if (!canPost(context) || reminder.kind == ReminderKind.WIDGET) return
        val id = idOf(reminder)
        val review = reminder.kind == ReminderKind.DAY_REVIEW
        val open = openIntent(context) {
            if (review) putExtra(EXTRA_REVIEW_DATE, reminder.itemId) else putExtra(EXTRA_TASK_ID, reminder.itemId)
        }
        val builder = NotificationCompat.Builder(context, if (review) REVIEWS_CHANNEL_ID else REMINDERS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (reminder.kind == ReminderKind.TASK) {
            builder.addAction(0, "Done", PlannerActionReceiver.taskDoneIntent(context, reminder.itemId, id))
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // The permission was taken away between the check and posting: nothing to show.
        }
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
}
