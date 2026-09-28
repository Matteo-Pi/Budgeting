package io.github.matteopi.budgeting.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.matteopi.budgeting.BudgetApp

/**
 * Runs once a day: posts due AUTO recurring/scheduled items. Accuracy snapshots, the reconcile
 * reminder notification and always-fresh widget refresh are added by WP-U4/WP-U5; every
 * repository write already asks [io.github.matteopi.budgeting.widget.WidgetUpdater] to refresh.
 */
class DailyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BudgetApp).container
        container.postingService.postDue(container.clock.today())
        return Result.success()
    }
}
