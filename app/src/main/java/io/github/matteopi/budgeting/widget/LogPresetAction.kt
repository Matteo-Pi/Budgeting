package io.github.matteopi.budgeting.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import io.github.matteopi.budgeting.BudgetApp
import kotlinx.coroutines.flow.first

/** Key for the preset id passed to [LogPresetAction] by a widget preset button. */
val PresetIdKey = ActionParameters.Key<Long>("preset_id")

/**
 * Logs a one-tap widget preset via [io.github.matteopi.budgeting.data.repo.TransactionRepository.logPreset],
 * refreshes the widget and shows a confirmation Toast. See docs/PLAN.md §6.7.
 */
class LogPresetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val presetId = parameters[PresetIdKey] ?: return
        val container = (context.applicationContext as BudgetApp).container
        val preset = container.presetRepository.observePresets().first().firstOrNull { it.id == presetId } ?: return
        val defaultAccountId = container.settingsStore.budgetSettings.first().defaultAccountId
        container.transactionRepository.logPreset(preset, defaultAccountId, container.clock.today())

        SafeToSpendWidget().updateAll(context)

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Logged ${preset.label} ${preset.amount.format()}", Toast.LENGTH_SHORT).show()
        }
    }
}
