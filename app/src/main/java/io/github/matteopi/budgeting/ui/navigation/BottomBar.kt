package io.github.matteopi.budgeting.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.AppIcons

private data class BottomBarItem(val route: Route, val label: String, val icon: ImageVector)

private val bottomBarItems = listOf(
    BottomBarItem(Route.Home, "Home", Icons.Default.Home),
    BottomBarItem(Route.Activity, "Activity", Icons.Default.List),
    BottomBarItem(Route.Forecast, "Forecast", Icons.Default.DateRange),
    BottomBarItem(Route.Plan, "Plan", Icons.Default.Edit),
    BottomBarItem(Route.Insights, "Insights", Icons.Default.Info),
)

private val recipesBarItems = listOf(
    BottomBarItem(Route.Recipes, "Recipes", AppIcons.MenuBook),
    BottomBarItem(Route.Diary, "Diary", AppIcons.EventNote),
    BottomBarItem(Route.Kitchen, "Kitchen", AppIcons.Kitchen),
    BottomBarItem(Route.Shopping, "Shopping", Icons.Default.ShoppingCart),
    BottomBarItem(Route.Ingredients, "Ingredients", AppIcons.Category),
)

private val plannerBarItems = listOf(
    BottomBarItem(Route.Today, "Today", AppIcons.Today),
    BottomBarItem(Route.Timetable, "Timetable", AppIcons.CalendarViewWeek),
    BottomBarItem(Route.Todo, "To-do", AppIcons.Checklist),
    BottomBarItem(Route.Habits, "Habits", AppIcons.Repeat),
    BottomBarItem(Route.PlannerInsights, "Insights", AppIcons.BarChart),
)

/** The 5-item bottom bar: Home, Activity, Forecast, Plan, Insights (docs/PLAN.md §6.5). */
@Composable
fun BudgetBottomBar(navController: NavHostController) {
    // A tab always opens its own screen, with only Home beneath it so Back returns to Home and the back
    // stack never piles up. Sub-screens left open (an account page, Regular costs, ...) are not brought
    // back: the routes are flat, so a restored sub-screen would leave no tab highlighted and tapping the
    // tab again would restore the same sub-screen instead of the tab.
    TabBar(
        navController = navController,
        items = bottomBarItems,
        section = AppSection.BUDGETING,
        firstTabOnStack = { runCatching { navController.getBackStackEntry<Route.Home>() }.isSuccess },
    ) { popUpTo<Route.Home>() }
}

/** The Food section's 5-item bottom bar: Recipes, Diary, Kitchen, Shopping, Ingredients (docs/PLAN.md §14.1). */
@Composable
fun RecipesBottomBar(navController: NavHostController) {
    // The same as Budgeting's, with the Recipes tab as the first screen.
    TabBar(
        navController = navController,
        items = recipesBarItems,
        section = AppSection.RECIPES,
        firstTabOnStack = { runCatching { navController.getBackStackEntry<Route.Recipes>() }.isSuccess },
    ) { popUpTo<Route.Recipes>() }
}

/** The Planner section's 5-item bottom bar: Today, Timetable, To-do, Habits, Insights (docs/PLAN.md §13.8). */
@Composable
fun PlannerBottomBar(navController: NavHostController) {
    TabBar(
        navController = navController,
        items = plannerBarItems,
        section = AppSection.PLANNER,
        firstTabOnStack = { runCatching { navController.getBackStackEntry<Route.Today>() }.isSuccess },
    ) { popUpTo<Route.Today>() }
}

/**
 * A bottom bar of [items] (the first is the section's first tab); [popUp] pops back to that first tab.
 *
 * The first tab can be missing from the back stack: a shared receipt opened while the app was in the other
 * section sits on top of that section's screens. A tap then switches section first (clearing the stack and
 * saving [section], as the top-left switch does), so the screens never pile up.
 */
@Composable
private fun TabBar(
    navController: NavHostController,
    items: List<BottomBarItem>,
    section: AppSection,
    firstTabOnStack: () -> Boolean,
    popUp: NavOptionsBuilder.() -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val switchSection = LocalSectionSwitch.current

    NavigationBar {
        items.forEach { item ->
            val selected = destination?.hierarchy?.any { it.hasRoute(item.route::class) } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        val stale = switchSection != null && !firstTabOnStack()
                        // Switching leaves the section's first tab open, so only another tab is navigated to.
                        if (stale) switchSection?.invoke(section)
                        if (!stale || item != items.first()) {
                            navController.navigate(item.route) {
                                popUp()
                                launchSingleTop = true
                            }
                        }
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}
