package io.github.matteopi.budgeting.ui.food.log

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.data.settings.AppSettings
import io.github.matteopi.budgeting.ui.theme.BudgetingTheme

/**
 * The quick log pop-up (docs/PLAN.md §14.6), launched from the Food widget's *+* and its batch names: *Log food* in a
 * sheet over a translucent, dimmed window, like `QuickAddActivity`. Tapping the dimmed area closes it. It shows no
 * money balances, so it needs no unlock, but honours "hide app content in recent apps".
 */
class QuickLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BudgetApp).container
        val batchId = intent.getLongExtra(EXTRA_BATCH_ID, -1L).takeIf { it > 0 }

        setContent {
            val appSettings by container.settingsStore.appSettings.collectAsStateWithLifecycle(initialValue = AppSettings())
            LaunchedEffect(appSettings.secureScreen) {
                if (appSettings.secureScreen) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            BudgetingTheme(theme = appSettings.theme, darkMode = appSettings.darkMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { finish() }
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).clickable(enabled = true) {},
                    ) {
                        LogFoodScreen(
                            epochDay = null,
                            meal = null,
                            batchId = batchId,
                            recipeId = null,
                            onBack = ::finish,
                            onDone = { message ->
                                Toast.makeText(this@QuickLogActivity, message, Toast.LENGTH_SHORT).show()
                                finish()
                            },
                            closeIcon = true,
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_BATCH_ID = "batch_id"

        fun intent(context: Context, batchId: Long? = null): Intent =
            Intent(context, QuickLogActivity::class.java).apply {
                if (batchId != null) putExtra(EXTRA_BATCH_ID, batchId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
