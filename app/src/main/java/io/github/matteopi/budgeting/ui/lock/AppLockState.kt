package io.github.matteopi.budgeting.ui.lock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the app should currently be considered locked: true on cold start, and true again
 * whenever the whole process has been backgrounded for more than [BACKGROUND_THRESHOLD_MILLIS]
 * (tracked via [ProcessLifecycleOwner], docs/PLAN.md §6.9). A successful [unlock] clears it.
 * This is a process-wide singleton (not tied to one Activity) since the "was the app in the
 * background" question is about the whole app, and `QuickAddActivity` deliberately never checks it.
 */
object AppLockState : DefaultLifecycleObserver {
    private const val BACKGROUND_THRESHOLD_MILLIS = 30_000L

    private var backgroundedAtMillis: Long? = null
    private var observing = false

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked

    /** Idempotent; call once (e.g. from `MainActivity.onCreate`) before reading [locked]. */
    fun ensureObserving() {
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAtMillis = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val backgroundedAt = backgroundedAtMillis ?: return
        if (System.currentTimeMillis() - backgroundedAt > BACKGROUND_THRESHOLD_MILLIS) {
            _locked.value = true
        }
    }

    fun unlock() {
        _locked.value = false
    }
}
