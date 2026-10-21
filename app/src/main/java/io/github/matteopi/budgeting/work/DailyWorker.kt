package io.github.matteopi.budgeting.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.R
import io.github.matteopi.budgeting.core.analysis.Accuracy
import io.github.matteopi.budgeting.widget.SafeToSpendWidget
import io.github.matteopi.budgeting.widget.planner.PlannerWidget
import kotlinx.coroutines.flow.first

/**
 * Runs once a day (docs/PLAN.md §6.8): posts due AUTO recurring/scheduled items; once a day
 * (guarded by `lastSnapshotDate`) saves an accuracy snapshot of the full forecast; refreshes the
 * widget; posts the weekly balance-check reminder when it's due ([ReminderRule]); forgets old dismissed study
 * suggestions; and, with the Planner writing to a calendar, brings that calendar up to date.
 */
class DailyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BudgetApp).container
        val today = container.clock.today()

        container.postingService.postDue(today)

        val appSettings = container.settingsStore.appSettings.first()
        val poolAccountIds = container.accountRepository.observeAccounts().first()
            .filter { it.includeInSafeToSpend && !it.archived }
            .map { it.id }
            .toSet()
        // No accounts yet means "£0 predicted, £0 actual" snapshots that would make the
        // accuracy screen look perfect, so only start recording once there is something to forecast.
        if (poolAccountIds.isNotEmpty() && appSettings.lastSnapshotDate != today) {
            val result = container.forecastService.full()
            val (balanceSnapshots, categorySnapshots) = Accuracy.snapshotsFor(result)
            container.snapshotRepository.saveSnapshots(balanceSnapshots, categorySnapshots)
            container.settingsStore.setLastSnapshotDate(today)
        }

        // Directly, not via the debounced WidgetUpdater: the worker may be stopped as soon as
        // doWork returns, and this is the refresh that rolls the widget over to a new day/week.
        SafeToSpendWidget().updateAll(applicationContext)

        val lastPoolReconciliation = container.accountRepository.observeReconciliations().first()
            .filter { it.accountId in poolAccountIds }
            .maxOfOrNull { it.date }

        val shouldRemind = ReminderRule.shouldRemind(
            enabled = appSettings.reconcileReminderEnabled,
            reminderDay = appSettings.reconcileReminderDay,
            today = today,
            lastPoolReconciliationDate = lastPoolReconciliation,
        )
        if (shouldRemind) {
            postReminderNotification(applicationContext)
        }

        // The Planner widget rolls over to the new day too.
        PlannerWidget.refresh(applicationContext)

        container.suggestionRepository.prune()

        // Writing back adds the days that came into range and drops what ended (docs/PLAN.md §13.7).
        if (container.settingsStore.plannerCalendars.first().writeTo != null) container.plannerCalendarSync.sync()

        return Result.success()
    }

    private fun postReminderNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, BudgetApp.REMINDERS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Check your balances")
            .setContentText("It's been a while since you last updated your account balances.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
    }

    private companion object {
        const val REMINDER_NOTIFICATION_ID = 1001
    }
}
