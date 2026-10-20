package io.github.matteopi.budgeting.work.planner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.matteopi.budgeting.BudgetApp
import java.util.concurrent.TimeUnit

/**
 * Writes the Planner into the chosen calendar off the main thread (docs/PLAN.md §13.7). Queued after Planner changes
 * (a 2-second delay, replaced by each newer request, so a burst of edits syncs once) and on app start.
 */
class CalendarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BudgetApp).container
        container.plannerCalendarSync.sync()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "calendar-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>().setInitialDelay(2, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
