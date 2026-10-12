package io.github.matteopi.budgeting.ui.quickadd

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.data.settings.AppSettings
import io.github.matteopi.budgeting.ui.receipts.ReceiptFiles
import io.github.matteopi.budgeting.ui.theme.BudgetingTheme

/**
 * Launched from the widget's "+" (and from Home/Activity's "+") over a translucent, dimmed
 * window (`Theme.Budgeting.QuickAdd`). Draws [QuickAddScreen] as a card at the bottom; tapping
 * the dimmed area finishes. Shows no balances, so it needs no app-lock check, but still honours
 * "hide app content in recent apps" via `FLAG_SECURE`. See docs/PLAN.md §6.6.
 */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw under the system bars on every Android version (15+ forces it anyway) and place the
        // card with insets below, so it always sits above the navigation bar and the keyboard.
        enableEdgeToEdge()
        val container = (application as BudgetApp).container
        val presetId = intent.getLongExtra(EXTRA_PRESET_ID, -1L).takeIf { it >= 0 }
        val flowExtra = intent.getStringExtra(EXTRA_FLOW)
            ?.let { name -> runCatching { CategoryFlow.valueOf(name) }.getOrNull() }

        setContent {
            val appSettings by container.settingsStore.appSettings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            LaunchedEffect(appSettings.secureScreen) {
                if (appSettings.secureScreen) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val viewModel: QuickAddViewModel = viewModel(
                factory = QuickAddViewModel.factory(container, presetId, flowExtra),
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            BudgetingTheme(theme = appSettings.theme, darkMode = appSettings.darkMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { finish() }
                        // Keep the card clear of the system bars and the keyboard (typing a label
                        // used to hide the Save button behind it).
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    QuickAddScreen(
                        state = state,
                        onFlowChange = viewModel::setFlow,
                        onKeypadKey = viewModel::onKeypadKey,
                        onCategorySelect = viewModel::selectCategory,
                        onAccountSelect = viewModel::selectAccount,
                        onPresetSelect = viewModel::applyPreset,
                        onLabelChange = viewModel::setLabel,
                        onDateChange = viewModel::setDate,
                        onSave = { viewModel.save(onSaved = ::finish) },
                        onOpenApp = { openApp() },
                        onScanReceipt = { scanReceipt() },
                        modifier = Modifier
                            .padding(16.dp)
                            // Absorb taps on the card itself so they don't fall through to the
                            // dimmed background's dismiss handler above.
                            .clickable(enabled = true) {},
                    )
                }
            }
        }
    }

    /** Opens the main app on the receipt scan screen (it is behind the app lock, which QuickAdd skips). */
    private fun scanReceipt() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ReceiptFiles.ACTION_SCAN_RECEIPT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val EXTRA_PRESET_ID = "preset_id"
        private const val EXTRA_FLOW = "flow"

        fun intent(context: Context, presetId: Long? = null, flow: CategoryFlow? = null): Intent =
            Intent(context, QuickAddActivity::class.java).apply {
                if (presetId != null) putExtra(EXTRA_PRESET_ID, presetId)
                if (flow != null) putExtra(EXTRA_FLOW, flow.name)
            }
    }
}
