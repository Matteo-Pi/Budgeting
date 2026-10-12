package io.github.matteopi.budgeting

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode
import io.github.matteopi.budgeting.ui.lock.AppLockState
import io.github.matteopi.budgeting.ui.lock.LockScreen
import io.github.matteopi.budgeting.ui.navigation.BudgetNavHost
import io.github.matteopi.budgeting.ui.navigation.Route
import io.github.matteopi.budgeting.ui.receipts.ReceiptFiles
import io.github.matteopi.budgeting.ui.theme.BudgetingTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires it (docs/PLAN.md §6.9).
class MainActivity : FragmentActivity() {

    /** A receipt screen to open once the navigation graph is up: from a shared receipt or quick add's "Scan receipt". */
    private val receiptRequest = MutableStateFlow<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLockState.ensureObserving()
        val container = (application as BudgetApp).container
        // Only for a fresh launch: after a rotation the same intent would be handled a second time.
        if (savedInstanceState == null) handleReceiptIntent(intent)
        setContent {
            // Collected outside the theme so the chosen theme/dark mode applies live, without a restart.
            val appSettings by container.settingsStore.appSettings.collectAsStateWithLifecycle(initialValue = null)
            val settings = appSettings
            BudgetingTheme(
                theme = settings?.theme ?: AppTheme.DEFAULT,
                darkMode = settings?.darkMode ?: DarkMode.DEFAULT,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // FLAG_SECURE ("hide in recent apps"): only touch the window when the setting
                    // actually changes, not on every recomposition.
                    LaunchedEffect(settings?.secureScreen) {
                        if (settings?.secureScreen == true) {
                            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                        } else if (settings != null) {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }

                    // Captured once, from the first settings emission, so it never changes the
                    // NavHost's start destination out from under an already-composed graph.
                    var startDestination by remember { mutableStateOf<Route?>(null) }
                    LaunchedEffect(settings?.onboardingDone) {
                        if (startDestination == null) {
                            settings?.let { startDestination = if (it.onboardingDone) Route.Home else Route.Onboarding }
                        }
                    }

                    val locked by AppLockState.locked.collectAsStateWithLifecycle()

                    if (settings == null || startDestination == null) {
                        LoadingScreen()
                    } else {
                        // The lock screen is drawn over the NavHost rather than replacing it, so
                        // locking keeps the user's place and never rebuilds the graph from the
                        // start destination captured at launch (which may be Onboarding).
                        // LockScreen is an opaque full-size Surface, which also blocks touches.
                        val navController = rememberNavController()
                        val receiptRoute by receiptRequest.collectAsStateWithLifecycle()
                        LaunchedEffect(receiptRoute) {
                            val route = receiptRoute
                            if (route != null) {
                                // Before onboarding there are no accounts to save a receipt into.
                                if (startDestination != Route.Onboarding) navController.navigate(route)
                                receiptRequest.value = null
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            BudgetNavHost(
                                navController = navController,
                                startDestination = startDestination!!,
                                container = container,
                            )
                            if (settings.appLockEnabled && locked) {
                                LockScreen(onUnlock = { AppLockState.unlock() })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleReceiptIntent(intent)
    }

    /**
     * Receipt entry points: `ACTION_SEND` (a photo, PDF or text shared into the app) is copied to
     * the cache folder, then opens the review screen; quick add's scan action opens the scan screen.
     * The app lock overlay already covers this activity, so nothing is shown before unlocking.
     */
    private fun handleReceiptIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ReceiptFiles.ACTION_SCAN_RECEIPT -> receiptRequest.value = Route.ReceiptScan
            Intent.ACTION_SEND -> {
                val shared = Intent(intent)
                val app = application as BudgetApp
                app.container.appScope.launch {
                    val route = ReceiptFiles.importShared(app, shared)
                    if (route != null) {
                        receiptRequest.value = route
                    } else {
                        runOnUiThread {
                            Toast.makeText(app, "Couldn't open that receipt", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
