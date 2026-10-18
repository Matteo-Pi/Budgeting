package io.github.matteopi.budgeting.ui.food.timers

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One recipe timer (docs/PLAN.md §11.11). [endsAt] is on the [CookingTimers] clock (`elapsedRealtime`, which keeps
 * counting while the phone sleeps); [onPhone] when the phone's clock app is timing it too.
 */
data class RunningTimer(
    val id: Long,
    val recipeId: Long,
    val recipeTitle: String,
    /** What it is for: the step's title, else "Step 3", or "Timer". */
    val label: String,
    val seconds: Int,
    val endsAt: Long,
    val onPhone: Boolean,
) {
    /** Whole seconds left at [now], rounded up so it shows 0:01 until the very end. */
    fun secondsLeft(now: Long): Int = ((endsAt - now + 999) / 1000).toInt().coerceAtLeast(0)
}

/**
 * The recipe timers, kept for the whole app (in `AppContainer`) so they run on while moving between the recipe,
 * the cooking view and other tabs. A timer that ends moves to [finished] until it is dismissed, which is when the
 * app says "Time's up". They are not saved: if Android stops the app they are gone, and the phone's own timer
 * (started alongside when the setting is on) still rings.
 */
class CookingTimers(
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val mutableRunning = MutableStateFlow<List<RunningTimer>>(emptyList())
    val running: StateFlow<List<RunningTimer>> = mutableRunning.asStateFlow()

    private val mutableFinished = MutableStateFlow<List<RunningTimer>>(emptyList())
    val finished: StateFlow<List<RunningTimer>> = mutableFinished.asStateFlow()

    private val jobs = HashMap<Long, Job>()
    private var nextId = 1L

    /** The clock the timers are measured on, for the screens' countdowns. */
    fun now(): Long = clock()

    /** Starts a timer of [seconds] and returns it. */
    @Synchronized
    fun start(recipeId: Long, recipeTitle: String, label: String, seconds: Int, onPhone: Boolean): RunningTimer {
        val timer = RunningTimer(nextId++, recipeId, recipeTitle, label, seconds.coerceAtLeast(1), now() + seconds * 1000L, onPhone)
        mutableRunning.update { it + timer }
        jobs[timer.id] = scope.launch {
            // Checked in short steps against the clock rather than one long delay, which would stop while the phone sleeps.
            while (true) {
                val left = timer.endsAt - now()
                if (left <= 0) break
                delay(minOf(left, 500L))
            }
            finish(timer.id)
        }
        return timer
    }

    /** Stops a running timer without saying anything. */
    @Synchronized
    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        mutableRunning.update { list -> list.filterNot { it.id == id } }
    }

    @Synchronized
    private fun finish(id: Long) {
        jobs.remove(id)
        val timer = mutableRunning.value.firstOrNull { it.id == id } ?: return
        mutableRunning.update { list -> list.filterNot { it.id == id } }
        mutableFinished.update { it + timer }
    }

    /** The "Time's up" for [id] has been seen. */
    fun dismiss(id: Long) {
        mutableFinished.update { list -> list.filterNot { it.id == id } }
    }
}
