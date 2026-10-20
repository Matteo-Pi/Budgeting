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

    /** `MainActivity` opens the Planner on this action; [EXTRA_TASK_ID] picks the task. */
    const val ACTION_OPEN_PLANNER = "io.github.matteopi.budgeting.OPEN_PLANNER"
    const val EXTRA_TASK_ID = "planner_task_id"

    fun createChannels(context: Context) {
        val channel = NotificationChannel(REMINDERS_CHANNEL_ID, "Planner reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tasks and deadlines you asked to be reminded of."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** True when the app may post notifications (always before Android 13). */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The notification id of [reminder]: the same reminder always replaces its own notification. */
    fun idOf(reminder: Reminder): Int = reminder.key.hashCode()

    fun post(context: Context, reminder: Reminder) {
        if (!canPost(context)) return
        val id = idOf(reminder)
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLANNER
            putExtra(EXTRA_TASK_ID, reminder.itemId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val builder = NotificationCompat.Builder(context, REMINDERS_CHANNEL_ID)
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
