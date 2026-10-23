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
import io.github.matteopi.budgeting.core.recipes.RecipeText
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode
import io.github.matteopi.budgeting.ui.importer.PdfStatementReader
import io.github.matteopi.budgeting.ui.lock.AppLockState
import io.github.matteopi.budgeting.ui.lock.LockScreen
import io.github.matteopi.budgeting.ui.navigation.BudgetNavHost
import io.github.matteopi.budgeting.ui.navigation.Route
import io.github.matteopi.budgeting.ui.navigation.firstTabOf
import io.github.matteopi.budgeting.ui.navigation.isFoodDestination
import io.github.matteopi.budgeting.ui.navigation.isPlannerDestination
import io.github.matteopi.budgeting.ui.navigation.plannerRoutes
import io.github.matteopi.budgeting.ui.receipts.ReceiptFiles
import io.github.matteopi.budgeting.ui.receipts.ReceiptInputKind
import io.github.matteopi.budgeting.ui.theme.BudgetingTheme
import io.github.matteopi.budgeting.work.planner.PlannerNotifications
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires it (docs/PLAN.md §6.9).
class MainActivity : FragmentActivity() {

    /**
     * A screen to open once the navigation graph is up: a task from a Planner reminder, the receipt review for a shared receipt, the scan
     * screen from quick add's "Scan receipt", or the statement import for a shared Santander statement PDF.
     */
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

                    // Captured once, from the first emission of both flows (the settings and the last-used
                    // section), so it never changes the NavHost's start destination out from under an
                    // already-composed graph. After onboarding the app opens in the section last used.
                    val appSection by container.settingsStore.appSection.collectAsStateWithLifecycle(initialValue = null)
                    val lastSection = appSection
                    var startDestination by remember { mutableStateOf<Route?>(null) }
                    LaunchedEffect(settings?.onboardingDone, lastSection) {
                        if (startDestination == null && settings != null && lastSection != null) {
                            startDestination = if (settings.onboardingDone) firstTabOf(lastSection) else Route.Onboarding
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
                                if (startDestination != Route.Onboarding) {
                                    val switching = plannerRoutes.any { it.isInstance(route) } &&
                                        !navController.currentBackStackEntry?.destination.isPlannerDestination()
                                    if (switching) {
                                        // A Planner reminder opens the Planner as a switch would, then the task on top.
                                        navController.navigate(Route.Today) { popUpTo(navController.graph.id) { inclusive = true } }
                                        container.appScope.launch { container.settingsStore.setAppSection(AppSection.PLANNER) }
                                    }
                                    if (route == Route.Diary && !navController.currentBackStackEntry?.destination.isFoodDestination()) {
                                        // The Food widget opens the Food section as a switch would, then the diary on top.
                                        navController.navigate(Route.Recipes) { popUpTo(navController.graph.id) { inclusive = true } }
                                        container.appScope.launch { container.settingsStore.setAppSection(AppSection.RECIPES) }
                                    }
                                    when {
                                        route != Route.Today -> navController.navigate(route)
                                        !switching -> navController.navigate(Route.Today) {
                                            popUpTo<Route.Today>()
                                            launchSingleTop = true
                                        }
                                    }
                                }
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
     * A shared PDF that turns out to be a Santander statement opens the statement import instead.
     * The app lock overlay already covers this activity, so nothing is shown before unlocking.
     */
    private fun handleReceiptIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            PlannerNotifications.ACTION_OPEN_PLANNER -> {
                val taskId = intent.getLongExtra(PlannerNotifications.EXTRA_TASK_ID, -1L)
                val habitId = intent.getLongExtra(PlannerNotifications.EXTRA_HABIT_ID, -1L)
                val reviewDate = intent.getLongExtra(PlannerNotifications.EXTRA_REVIEW_DATE, Long.MIN_VALUE)
                receiptRequest.value = when {
                    reviewDate != Long.MIN_VALUE -> Route.Day(epochDay = reviewDate, review = true)
                    intent.getBooleanExtra(PlannerNotifications.EXTRA_WEEKLY_REVIEW, false) -> Route.WeeklyReview
                    intent.getBooleanExtra(PlannerNotifications.EXTRA_TODAY, false) -> Route.Today
                    taskId >= 0 -> Route.TaskDetail(taskId)
                    habitId >= 0 -> Route.HabitDetail(habitId)
                    else -> Route.Todo
                }
            }
            ReceiptFiles.ACTION_SCAN_RECEIPT -> receiptRequest.value = Route.ReceiptScan
            ACTION_OPEN_DIARY -> receiptRequest.value = Route.Diary
            Intent.ACTION_SEND -> {
                val shared = Intent(intent)
                val app = application as BudgetApp
                app.container.appScope.launch {
                    // Shared text that reads as a recipe (an Ingredients heading and a method) opens the recipe
                    // review instead (docs/PLAN.md §11.8); a receipt never has those headings.
                    val text = shared.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    if (!shared.hasExtra(Intent.EXTRA_STREAM) && text != null && RecipeText.looksLikeRecipe(text)) {
                        receiptRequest.value = Route.AddRecipe(textPath = ReceiptFiles.writeText(app, text).absolutePath)
                        return@launch
                    }
                    val review = ReceiptFiles.importShared(app, shared)
                    val route: Route? = if (
                        review != null &&
                        review.kind == ReceiptInputKind.PDF.name &&
                        PdfStatementReader.isSantanderStatement(app, File(review.path))
                    ) {
                        Route.StatementImport(pdfPath = review.path)
                    } else {
                        review
                    }
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

    companion object {
        /** Opens the Food section's diary (the Food widget, docs/PLAN.md §14.6). */
        const val ACTION_OPEN_DIARY = "io.github.matteopi.budgeting.OPEN_DIARY"
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }

}
