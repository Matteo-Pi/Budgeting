package io.github.matteopi.budgeting.ui.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import io.github.matteopi.budgeting.data.settings.AppSection
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

/** Type-safe navigation destinations for every screen in docs/PLAN.md §6.5. */
sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data object Activity : Route
    @Serializable data object Forecast : Route
    @Serializable data object Plan : Route
    @Serializable data object Insights : Route
    @Serializable data object Settings : Route

    @Serializable data class AccountDetail(val accountId: Long) : Route

    /** One account's full history: balance chart, summary, filters and every transaction with its running balance. */
    @Serializable data class AccountHistory(val accountId: Long) : Route
    @Serializable data class AccountEdit(val accountId: Long? = null) : Route

    @Serializable data object Predictions : Route
    @Serializable data object Scenarios : Route
    @Serializable data class ScenarioEdit(val scenarioId: Long? = null) : Route
    @Serializable data object Timeline : Route
    @Serializable data object Accuracy : Route

    @Serializable data object RecurringList : Route
    @Serializable data class RecurringEdit(val ruleId: Long? = null) : Route
    @Serializable data object ScheduledList : Route
    @Serializable data class ScheduledEdit(val itemId: Long? = null) : Route
    @Serializable data object TermDates : Route
    @Serializable data object StudentLoanHelper : Route
    @Serializable data object SinkingFunds : Route
    @Serializable data class SinkingFundEdit(val fundId: Long? = null) : Route

    @Serializable data object Categories : Route
    @Serializable data object Presets : Route
    @Serializable data object Backup : Route

    /**
     * The statement import wizard. [pdfPath] is set when a PDF statement was shared into the app: a file
     * in the app's cache that the wizard reads when it opens (docs/PLAN.md §5.16).
     */
    @Serializable data class StatementImport(val pdfPath: String? = null) : Route

    @Serializable data object Onboarding : Route

    @Serializable data object Receipts : Route
    @Serializable data class ReceiptDetail(val receiptId: Long) : Route
    @Serializable data class ItemDetail(val productId: Long) : Route
    /** Receipt capture (docs/PLAN.md §10): choose camera or gallery, then continue to [ReceiptReview]. */
    @Serializable data object ReceiptScan : Route

    /** Review a read receipt before saving: [path] is a file in the app's cache, [kind] a `ReceiptInputKind` name. */
    @Serializable data class ReceiptReview(val path: String, val kind: String) : Route

    // ----- The Recipes section (docs/PLAN.md §11.8) -----

    /** The Recipes section's first tab: its recipe list. */
    @Serializable data object Recipes : Route
    @Serializable data object Compare : Route
    @Serializable data object Shopping : Route
    @Serializable data object Ingredients : Route

    @Serializable data class RecipeDetail(val recipeId: Long) : Route

    /** The cooking view of one recipe: no bottom bar. */
    @Serializable data class Cooking(val recipeId: Long) : Route

    /** Add a recipe: paste or type it. [textPath] is a text file in the app's cache holding shared text, read when the screen opens. */
    @Serializable data class AddRecipe(val textPath: String? = null) : Route
    @Serializable data class EditRecipe(val recipeId: Long) : Route
    /** [barcode]: a barcode scanned on the Ingredients tab that no ingredient has yet, for a new one. */
    @Serializable data class IngredientEdit(val ingredientId: Long? = null, val barcode: String? = null) : Route
    @Serializable data object RecipeSettings : Route

    // ----- The Planner section (docs/PLAN.md §13.8) -----

    /** The Planner's first tab. */
    @Serializable data object Today : Route
    @Serializable data object Timetable : Route
    @Serializable data object Todo : Route
    @Serializable data object Habits : Route
    @Serializable data object PlannerInsights : Route

    /**
     * Add or edit a task. [parentId] adds a step of that deadline; [kind] (a `TaskKind` name) starts a new one as a
     * deadline; [text] prefills a new one from the To-do tab's quick-add line.
     */
    @Serializable data class TaskEdit(
        val taskId: Long? = null,
        val parentId: Long? = null,
        val kind: String? = null,
        val text: String? = null,
    ) : Route

    /** One task's page: its details, checklist, and a deadline's status and steps. */
    @Serializable data class TaskDetail(val taskId: Long) : Route
    @Serializable data object PlannerSettings : Route
    @Serializable data object Modules : Route
    /** The breakdown templates for splitting deadlines into steps. */
    @Serializable data object Templates : Route

    /** Add ([entryId] null) or edit a timetable entry. */
    @Serializable data class EntryEdit(val entryId: Long? = null) : Route

    /** Which phone calendars the Planner shows, and writing back. */
    @Serializable data object PlannerCalendars : Route

    /** The same term dates as Budgeting's, reached from the Planner so it keeps its own bottom bar. */
    @Serializable data object PlannerTermDates : Route

    /** One day's record ([epochDay]); [review] opens it for the end-of-day review. */
    @Serializable data class Day(val epochDay: Long, val review: Boolean = false) : Route

    /** Where the day starts and ends, and the wake and sleep times. */
    @Serializable data object PlannerDaySettings : Route

    /** The day record's categories. */
    @Serializable data object PlannerCategories : Route

    /** Add ([habitId] null) or edit a habit. */
    @Serializable data class HabitEdit(val habitId: Long? = null) : Route

    /** One habit's page: its month, rates and streaks, *Pause* and *Archive*. */
    @Serializable data class HabitDetail(val habitId: Long) : Route

    /** Meals, travel and the shortest useful gap. */
    @Serializable data object PlannerFreeTime : Route

    /** Suggested study: times, days off, blocks, caps, revision and modules. */
    @Serializable data object PlannerStudy : Route

    /** The weekly review: how the week went, then next week's suggestions to accept, move or turn down. */
    @Serializable data object WeeklyReview : Route
}

/**
 * Every route of the Recipes section: its four tabs and its sub-screens. A destination in this list belongs
 * to the Recipes section (it shows the Recipes bottom bar); any other destination belongs to Budgeting.
 */
val foodRoutes: List<KClass<out Route>> = listOf(
    Route.Recipes::class,
    Route.Compare::class,
    Route.Shopping::class,
    Route.Ingredients::class,
    Route.RecipeDetail::class,
    Route.Cooking::class,
    Route.AddRecipe::class,
    Route.EditRecipe::class,
    Route.IngredientEdit::class,
    Route.RecipeSettings::class,
)

/** True if this destination is one of [foodRoutes]. */
fun NavDestination?.isFoodDestination(): Boolean =
    this?.hierarchy?.any { destination -> foodRoutes.any { destination.hasRoute(it) } } == true

/**
 * Every route of the Planner section: its five tabs and its sub-screens. A destination in this list shows the
 * Planner's bottom bar.
 */
val plannerRoutes: List<KClass<out Route>> = listOf(
    Route.Today::class,
    Route.Timetable::class,
    Route.Todo::class,
    Route.Habits::class,
    Route.PlannerInsights::class,
    Route.TaskEdit::class,
    Route.TaskDetail::class,
    Route.PlannerSettings::class,
    Route.Modules::class,
    Route.Templates::class,
    Route.EntryEdit::class,
    Route.PlannerCalendars::class,
    Route.PlannerTermDates::class,
    Route.Day::class,
    Route.PlannerDaySettings::class,
    Route.PlannerCategories::class,
    Route.HabitEdit::class,
    Route.HabitDetail::class,
    Route.PlannerFreeTime::class,
    Route.PlannerStudy::class,
    Route.WeeklyReview::class,
)

/** True if this destination is one of [plannerRoutes]. */
fun NavDestination?.isPlannerDestination(): Boolean =
    this?.hierarchy?.any { destination -> plannerRoutes.any { destination.hasRoute(it) } } == true

/** The first tab of [section]: where switching to it lands. */
fun firstTabOf(section: AppSection): Route = when (section) {
    AppSection.BUDGETING -> Route.Home
    AppSection.RECIPES -> Route.Recipes
    AppSection.PLANNER -> Route.Today
}
