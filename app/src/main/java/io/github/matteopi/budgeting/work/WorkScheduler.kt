package io.github.matteopi.budgeting.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Schedules [DailyWorker] to run once a day, first around 03:00 local time. */
object WorkScheduler {
    private const val UNIQUE_WORK_NAME = "daily"
    private val FIRST_RUN_TIME: LocalTime = LocalTime.of(3, 0)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Milliseconds from [now] to the next 03:00 (today's if it hasn't passed yet, else tomorrow's). */
    internal fun initialDelayMillis(now: LocalDateTime = LocalDateTime.now()): Long {
        var next = LocalDateTime.of(now.toLocalDate(), FIRST_RUN_TIME)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        return Duration.between(now, next).toMillis()
    }
}
