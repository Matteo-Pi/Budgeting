package io.github.matteopi.budgeting.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.accounts.AccountDetailScreen
import io.github.matteopi.budgeting.ui.accounts.AccountEditScreen
import io.github.matteopi.budgeting.ui.accounts.AccountHistoryScreen
import io.github.matteopi.budgeting.ui.activity.ActivityScreen
import io.github.matteopi.budgeting.ui.food.CompareScreen
import io.github.matteopi.budgeting.ui.food.IngredientsScreen
import io.github.matteopi.budgeting.ui.food.RecipeSettingsScreen
import io.github.matteopi.budgeting.ui.food.RecipesScreen
import io.github.matteopi.budgeting.ui.food.ShoppingScreen
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientEditScreen
import io.github.matteopi.budgeting.ui.food.recipe.CookingScreen
import io.github.matteopi.budgeting.ui.food.recipe.RecipeDetailScreen
import io.github.matteopi.budgeting.ui.food.review.AddRecipeScreen
import io.github.matteopi.budgeting.ui.food.review.EditRecipeScreen
import io.github.matteopi.budgeting.ui.food.timers.TimerAlerts
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
import io.github.matteopi.budgeting.ui.receipts.ReceiptReviewScreen
import io.github.matteopi.budgeting.ui.receipts.ReceiptScanScreen
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptDetailScreen
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptsScreen
import io.github.matteopi.budgeting.ui.settings.CategoriesScreen
import io.github.matteopi.budgeting.ui.settings.PresetsScreen
import io.github.matteopi.budgeting.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * The app's single NavHost, wrapped in a Scaffold with the bottom bar of the section the screen belongs to
 * (docs/PLAN.md §11.8, §13.8): [PlannerBottomBar] on the Planner's routes ([plannerRoutes]), [RecipesBottomBar]
 * on the Recipes section's ([foodRoutes]), no bar on the cooking view or onboarding, and [BudgetBottomBar]
 * everywhere else.
 *
 * It also gives every screen the section switch through [LocalSectionSwitch]. Switching clears the back
 * stack and opens the chosen section's first tab (so Back there leaves the app), then saves the choice
 * through `container.settingsStore` so the app opens in that section next time.
 */
@Composable
fun BudgetNavHost(
    navController: NavHostController,
    startDestination: Route,
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination != null &&
        !destination.hasRoute<Route.Onboarding>() &&
        !destination.hasRoute<Route.Cooking>()
    val inRecipes = destination.isFoodDestination()
    val inPlanner = destination.isPlannerDestination()
    val context = LocalContext.current
    val onAddTransaction = { context.startActivity(QuickAddActivity.intent(context)) }

    val scope = rememberCoroutineScope()
    val switchTo: (AppSection) -> Unit = remember(navController, container, scope) {
        { section: AppSection ->
            navController.navigate(firstTabOf(section)) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
            scope.launch { container.settingsStore.setAppSection(section) }
            Unit
        }
    }

    CompositionLocalProvider(LocalSectionSwitch provides switchTo) {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                if (showBottomBar) {
                    when {
                        inPlanner -> PlannerBottomBar(navController)
                        inRecipes -> RecipesBottomBar(navController)
                        else -> BudgetBottomBar(navController)
                    }
                }
            },
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
                        onScanReceipt = { navController.navigate(Route.ReceiptScan) },
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
                        onOpenStatementImport = { navController.navigate(Route.StatementImport()) },
                        onOpenReceipts = { navController.navigate(Route.Receipts) },
                    )
                }
                composable<Route.AccountDetail> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.AccountDetail>()
                    AccountDetailScreen(
                        accountId = args.accountId,
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate(Route.AccountEdit(id)) },
                        onOpenHistory = { id -> navController.navigate(Route.AccountHistory(id)) },
                        onOpenReceipt = { id -> navController.navigate(Route.ReceiptDetail(id)) },
                    )
                }
                composable<Route.AccountHistory> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.AccountHistory>()
                    AccountHistoryScreen(
                        accountId = args.accountId,
                        onBack = { navController.popBackStack() },
                        onOpenReceipt = { id -> navController.navigate(Route.ReceiptDetail(id)) },
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
                composable<Route.StatementImport> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.StatementImport>()
                    StatementImportScreen(onBack = { navController.popBackStack() }, pdfPath = args.pdfPath)
                }
                composable<Route.Onboarding> {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Route.Home) {
                                popUpTo<Route.Onboarding> { inclusive = true }
                            }
                        },
                        onOpenStatementImport = { navController.navigate(Route.StatementImport()) },
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
                composable<Route.ReceiptScan> {
                    ReceiptScanScreen(
                        onBack = { navController.popBackStack() },
                        // The scan screen is replaced by the review, so Back from the review returns to where scanning started.
                        onCaptured = { review ->
                            navController.navigate(review) {
                                popUpTo<Route.ReceiptScan> { inclusive = true }
                            }
                        },
                    )
                }
                composable<Route.ReceiptReview> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.ReceiptReview>()
                    ReceiptReviewScreen(path = args.path, kind = args.kind, onBack = { navController.popBackStack() })
                }

                // ----- The Recipes section (docs/PLAN.md §11.8). -----
                composable<Route.Recipes> {
                    RecipesScreen(
                        onOpenSettings = { navController.navigate(Route.RecipeSettings) },
                        onOpenRecipe = { id -> navController.navigate(Route.RecipeDetail(id)) },
                        onAddRecipe = { navController.navigate(Route.AddRecipe()) },
                    )
                }
                composable<Route.Compare> {
                    CompareScreen(onOpenRecipe = { id -> navController.navigate(Route.RecipeDetail(id)) })
                }
                composable<Route.Shopping> {
                    ShoppingScreen()
                }
                composable<Route.Ingredients> {
                    IngredientsScreen(
                        onAddIngredient = { navController.navigate(Route.IngredientEdit()) },
                        onOpenIngredient = { id -> navController.navigate(Route.IngredientEdit(id)) },
                        onNewWithBarcode = { code -> navController.navigate(Route.IngredientEdit(barcode = code)) },
                    )
                }
                composable<Route.RecipeDetail> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.RecipeDetail>()
                    RecipeDetailScreen(
                        recipeId = args.recipeId,
                        onBack = { navController.popBackStack() },
                        onCook = { id -> navController.navigate(Route.Cooking(id)) },
                        onEdit = { id -> navController.navigate(Route.EditRecipe(id)) },
                    )
                }
                composable<Route.Cooking> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.Cooking>()
                    CookingScreen(recipeId = args.recipeId, onBack = { navController.popBackStack() })
                }
                composable<Route.AddRecipe> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.AddRecipe>()
                    AddRecipeScreen(
                        textPath = args.textPath,
                        onBack = { navController.popBackStack() },
                        // The new recipe replaces the add screen, so Back from it returns to where adding started.
                        onSaved = { id ->
                            navController.navigate(Route.RecipeDetail(id)) {
                                popUpTo<Route.AddRecipe> { inclusive = true }
                            }
                        },
                    )
                }
                composable<Route.EditRecipe> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.EditRecipe>()
                    EditRecipeScreen(
                        recipeId = args.recipeId,
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() },
                    )
                }
                composable<Route.IngredientEdit> { backStackEntry ->
                    val args = backStackEntry.toRoute<Route.IngredientEdit>()
                    IngredientEditScreen(
                        ingredientId = args.ingredientId,
                        barcode = args.barcode,
                        onBack = { navController.popBackStack() },
                        onOpenRecipe = { id -> navController.navigate(Route.RecipeDetail(id)) },
                    )
                }
                composable<Route.RecipeSettings> {
                    RecipeSettingsScreen(onBack = { navController.popBackStack() })
                }

                // ----- The Planner section (docs/PLAN.md §13.8). -----
                plannerDestinations(navController)
            }
        }
        // A recipe timer that ends says so on whatever screen is showing (docs/PLAN.md §11.11).
        TimerAlerts()
    }
}
