package io.github.matteopi.budgeting.widget.food

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces what follows a diary or kitchen write (docs/PLAN.md §14.6): the Food widget is redrawn once,
 * [DEBOUNCE_MILLIS] after the last of several writes. [onChanged] is given by `AppContainer`.
 */
class FoodUpdater(
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
