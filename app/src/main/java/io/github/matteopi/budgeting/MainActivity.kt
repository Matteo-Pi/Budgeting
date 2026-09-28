package io.github.matteopi.budgeting

import android.os.Bundle
import android.view.WindowManager
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
import io.github.matteopi.budgeting.ui.theme.BudgetingTheme

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires it (docs/PLAN.md §6.9).
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLockState.ensureObserving()
        val container = (application as BudgetApp).container
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
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
