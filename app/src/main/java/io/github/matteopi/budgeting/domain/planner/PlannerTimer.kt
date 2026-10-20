package io.github.matteopi.budgeting.domain.planner

import android.content.Context
import io.github.matteopi.budgeting.core.planner.day.DayEdits
import io.github.matteopi.budgeting.core.planner.day.EditPlan
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RecordOrigin
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.data.settings.TimerState
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.work.planner.PlannerNotifications
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What stopping the timer did. */
sealed interface TimerStop {
    /** The run was shorter than a chunk can be, so nothing was recorded. */
    data class TooShort(val minutes: Long) : TimerStop

    /** A record from the start to now, which won over what it overlapped; [plan] undoes it. */
    data class Recorded(val chunk: RecordChunk, val plan: EditPlan<RecordChunk>) : TimerStop
}

/**
 * The Planner's timer (docs/PLAN.md §13.7). It is only a start time kept in settings, so it needs no service and
 * survives Android stopping the app; an ongoing notification shows it, with *Stop*.
 */
class PlannerTimer(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val dayRepository: DayRepository,
    private val moduleRepository: ModuleRepository,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    val state: Flow<TimerState?> = settingsStore.plannerTimer

    /** Starts recording [categoryId] (stopping a running timer first, which is returned). */
    suspend fun start(categoryId: Long, moduleId: Long? = null, taskId: Long? = null): TimerStop? = mutex.withLock {
        val stopped = stopLocked()
        val state = TimerState(clock.nowMillis(), categoryId, moduleId, taskId)
        settingsStore.setPlannerTimer(state)
        showNotification(state)
        stopped
    }

    /** Stops the timer, recording from its start to now. Null when none was running. */
    suspend fun stop(): TimerStop? = mutex.withLock { stopLocked() }

    /** After the app starts: the notification again for a timer that was running (a restart clears it). */
    suspend fun restoreNotification() {
        settingsStore.plannerTimer.first()?.let { showNotification(it) }
    }

    private suspend fun stopLocked(): TimerStop? {
        val state = settingsStore.plannerTimer.first() ?: return null
        settingsStore.setPlannerTimer(null)
        PlannerNotifications.cancelTimer(context)
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val minutes = Duration.between(state.start, now).toMinutes()
        if (minutes < DayEdits.MIN_MINUTES) return TimerStop.TooShort(minutes)
        val chunk = RecordChunk(
            start = state.start, end = now, categoryId = state.categoryId, moduleId = state.moduleId, taskId = state.taskId,
            origin = RecordOrigin.TIMER,
        )
        val plan = DayEdits.place(dayRepository.getRecords(Interval(state.start, now)), chunk) ?: return TimerStop.TooShort(minutes)
        val applied = dayRepository.applyRecords(plan)
        return TimerStop.Recorded(applied.inserts.firstOrNull { it.origin == RecordOrigin.TIMER && it.start == state.start } ?: chunk, applied)
    }

    private suspend fun showNotification(state: TimerState) {
        val category = dayRepository.getCategories().firstOrNull { it.id == state.categoryId }?.name ?: "Timer"
        val module = state.moduleId?.let { id -> moduleRepository.getModules().firstOrNull { it.id == id }?.code }
        PlannerNotifications.showTimer(context, listOfNotNull(category, module).joinToString(" · "), state.startMillis)
    }
}
