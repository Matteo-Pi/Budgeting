package io.github.matteopi.budgeting.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.backup.BackupScreen
import io.github.matteopi.budgeting.ui.accounts.AccountDetailScreen
import io.github.matteopi.budgeting.ui.accounts.AccountEditScreen
import io.github.matteopi.budgeting.ui.activity.ActivityScreen
import io.github.matteopi.budgeting.ui.forecast.AccuracyScreen
import io.github.matteopi.budgeting.ui.forecast.ForecastScreen
import io.github.matteopi.budgeting.ui.forecast.PredictionsScreen
import io.github.matteopi.budgeting.ui.forecast.ScenarioEditScreen
import io.github.matteopi.budgeting.ui.forecast.ScenariosScreen
import io.github.matteopi.budgeting.ui.forecast.TimelineScreen
import io.github.matteopi.budgeting.ui.home.HomeScreen
import io.github.matteopi.budgeting.ui.importer.StatementImportScreen
import io.github.matteopi.budgeting.ui.insights.InsightsScreen
import io.github.matteopi.budgeting.ui.insights.ItemDetailScreen
import io.github.matteopi.budgeting.ui.onboarding.OnboardingScreen
import io.github.matteopi.budgeting.ui.plan.PlanScreen
import io.github.matteopi.budgeting.ui.plan.RecurringEditScreen
import io.github.matteopi.budgeting.ui.plan.RecurringListScreen
import io.github.matteopi.budgeting.ui.plan.ScheduledEditScreen
import io.github.matteopi.budgeting.ui.plan.ScheduledListScreen
import io.github.matteopi.budgeting.ui.plan.SinkingFundEditScreen
import io.github.matteopi.budgeting.ui.plan.SinkingFundsScreen
import io.github.matteopi.budgeting.ui.plan.StudentLoanHelperScreen
import io.github.matteopi.budgeting.ui.plan.TermDatesScreen
import io.github.matteopi.budgeting.ui.quickadd.QuickAddActivity
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptDetailScreen
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptsScreen
import io.github.matteopi.budgeting.ui.settings.CategoriesScreen
import io.github.matteopi.budgeting.ui.settings.PresetsScreen
import io.github.matteopi.budgeting.ui.settings.SettingsScreen

/**
 * The app's single NavHost, wrapped in a Scaffold that shows [BudgetBottomBar] on every screen
 * except onboarding. [container] is unused directly here today (screens will read it via a
 * ViewModel factory once they have real content) but is threaded through so later packages
 * don't need to change this signature.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BudgetNavHost(
    navController: NavHostController,
    startDestination: Route,
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination != null && !destination.hasRoute<Route.Onboarding>()
    val context = LocalContext.current
    val onAddTransaction = { context.startActivity(QuickAddActivity.intent(context)) }

    Scaffold(
        modifier = modifier,
        bottomBar = { if (showBottomBar) BudgetBottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            // The bottom bar already covers the system navigation bar, so mark that inset as used:
            // otherwise each screen's own Scaffold reserves it again, leaving a blank strip above
            // the bottom bar. imePadding keeps forms (and their Save buttons) above the keyboard.
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            composable<Route.Home> {
                HomeScreen(
                    onOpenSettings = { navController.navigate(Route.Settings) },
                    onOpenAccount = { id -> navController.navigate(Route.AccountDetail(id)) },
                    onOpenAccountEdit = { id -> navController.navigate(Route.AccountEdit(id)) },
                    onOpenActivity = { navController.navigate(Route.Activity) },
                    onOpenForecast = { navController.navigate(Route.Forecast) },
                    onOpenPredictions = { navController.navigate(Route.Predictions) },
                    onAddTransaction = onAddTransaction,
                    onOpenSinkingFunds = { navController.navigate(Route.SinkingFunds) },
                )
            }
            composable<Route.Activity> {
                ActivityScreen(
                    onAddTransaction = onAddTransaction,
                    onOpenReceipt = { id -> navController.navigate(Route.ReceiptDetail(id)) },
                )
            }
            composable<Route.Forecast> {
                ForecastScreen(
                    onOpenPredictions = { navController.navigate(Route.Predictions) },
                    onOpenScenarios = { navController.navigate(Route.Scenarios) },
                    onOpenTimeline = { navController.navigate(Route.Timeline) },
                    onOpenAccuracy = { navController.navigate(Route.Accuracy) },
                )
            }
            composable<Route.Plan> {
                PlanScreen(
                    onOpenRecurring = { navController.navigate(Route.RecurringList) },
                    onOpenScheduled = { navController.navigate(Route.ScheduledList) },
                    onOpenTermDates = { navController.navigate(Route.TermDates) },
                    onOpenStudentLoanHelper = { navController.navigate(Route.StudentLoanHelper) },
                    onOpenSinkingFunds = { navController.navigate(Route.SinkingFunds) },
                    onOpenPredictions = { navController.navigate(Route.Predictions) },
                )
            }
            composable<Route.Insights> {
                InsightsScreen(onOpenItem = { id -> navController.navigate(Route.ItemDetail(id)) })
            }
            composable<Route.Settings> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCategories = { navController.navigate(Route.Categories) },
                    onOpenPresets = { navController.navigate(Route.Presets) },
                    onOpenBackup = { navController.navigate(Route.Backup) },
                    onOpenStatementImport = { navController.navigate(Route.StatementImport) },
                    onOpenReceipts = { navController.navigate(Route.Receipts) },
                )
            }
            composable<Route.AccountDetail> { backStackEntry ->
                val args = backStackEntry.toRoute<Route.AccountDetail>()
                AccountDetailScreen(
                    accountId = args.accountId,
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Route.AccountEdit(id)) },
                )
            }
            composable<Route.AccountEdit> { backStackEntry ->
                val args = backStackEntry.toRoute<Route.AccountEdit>()
                AccountEditScreen(
                    accountId = args.accountId,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onRemoved = {
                        // The account is gone from every list, so don't return to its detail screen.
                        if (!navController.popBackStack<Route.AccountDetail>(inclusive = true)) {
                            navController.popBackStack()
                        }
                    },
                )
            }
            composable<Route.Predictions> {
                PredictionsScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.Scenarios> {
                ScenariosScreen(
                    onBack = { navController.popBackStack() },
                    onOpenScenario = { id -> navController.navigate(Route.ScenarioEdit(id)) },
                )
            }
            composable<Route.ScenarioEdit> { backStackEntry ->
                val args = backStackEntry.toRoute<Route.ScenarioEdit>()
                ScenarioEditScreen(scenarioId = args.scenarioId, onBack = { navController.popBackStack() })
            }
            composable<Route.Timeline> {
                TimelineScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.Accuracy> {
                AccuracyScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.RecurringList> {
                RecurringListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRecurring = { id -> navController.navigate(Route.RecurringEdit(id)) },
                )
            }
            composable<Route.RecurringEdit> { backStackEntry ->
                val args = backStackEntry.toRoute<Route.RecurringEdit>()
                RecurringEditScreen(ruleId = args.ruleId, onBack = { navController.popBackStack() })
            }
            composable<Route.ScheduledList> {
                ScheduledListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenScheduled = { id -> navController.navigate(Route.ScheduledEdit(id)) },
                )
            }
            composable<Route.ScheduledEdit> { backStackEntry ->
                val args = backStackEntry.toRoute<Route.ScheduledEdit>()
                ScheduledEditScreen(itemId = args.itemId, onBack = { navController.popBackStack() })
            }
            composable<Route.TermDates> {
                TermDatesScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.StudentLoanHelper> {
                StudentLoanHelperScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.SinkingFunds> {
                SinkingFundsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenFund = { id -> navController.navigate(Route.SinkingFundEdit(id)) },
                )
            }
            composable<Route.SinkingFundEdit> { backStackEntry ->
                val args = backStackEntry.toRoute<Route.SinkingFundEdit>()
                SinkingFundEditScreen(fundId = args.fundId, onBack = { navController.popBackStack() })
            }
            composable<Route.Categories> {
                CategoriesScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.Presets> {
                PresetsScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.Backup> {
                BackupScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.StatementImport> {
                StatementImportScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.Onboarding> {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Route.Home) {
                            popUpTo<Route.Onboarding> { inclusive = true }
                        }
                    },
                    onOpenStatementImport = { navController.navigate(Route.StatementImport) },
                )
            }
            composable<Route.Receipts> {
                ReceiptsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReceipt = { id -> navController.navigate(Route.ReceiptDetail(id)) },
                )
            }
            composable<Route.ReceiptDetail> { backStackEntry ->
                val args = backStackEntry.toRoute<Route.ReceiptDetail>()
                ReceiptDetailScreen(receiptId = args.receiptId, onBack = { navController.popBackStack() })
            }
            composable<Route.ItemDetail> { backStackEntry ->
                val args = backStackEntry.toRoute<Route.ItemDetail>()
                ItemDetailScreen(productId = args.productId, onBack = { navController.popBackStack() })
            }
        }
    }
}
