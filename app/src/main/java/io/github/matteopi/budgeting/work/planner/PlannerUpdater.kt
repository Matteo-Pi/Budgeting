package io.github.matteopi.budgeting.work.planner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces what follows a Planner write (docs/PLAN.md §13.5): many writes in quick succession reschedule the
 * reminder alarm once, [DEBOUNCE_MILLIS] after the last. [onChanged] is given by `AppContainer`, which builds the
 * alarm after the repositories that call this.
 */
class PlannerUpdater(
    private val scope: CoroutineScope,
    private val onChanged: suspend () -> Unit,
) {
    private var pending: Job? = null

    @Synchronized
    fun changed() {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MILLIS)
            onChanged()
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}
