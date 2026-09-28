package io.github.matteopi.budgeting.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces widget refresh requests: every repository write asks for an update, but many
 * writes in quick succession (e.g. a statement import) should only redraw the widget once,
 * [DEBOUNCE_MILLIS] after the last request.
 */
class WidgetUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var pending: Job? = null

    fun requestUpdate() {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MILLIS)
            SafeToSpendWidget().updateAll(context)
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}
