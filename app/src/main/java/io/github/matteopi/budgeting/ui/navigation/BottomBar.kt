package io.github.matteopi.budgeting.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
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
import androidx.navigation.compose.currentBackStackEntryAsState

private data class BottomBarItem(val route: Route, val label: String, val icon: ImageVector)

private val bottomBarItems = listOf(
    BottomBarItem(Route.Home, "Home", Icons.Default.Home),
    BottomBarItem(Route.Activity, "Activity", Icons.Default.List),
    BottomBarItem(Route.Forecast, "Forecast", Icons.Default.DateRange),
    BottomBarItem(Route.Plan, "Plan", Icons.Default.Edit),
    BottomBarItem(Route.Insights, "Insights", Icons.Default.Info),
)

/** The 5-item bottom bar: Home, Activity, Forecast, Plan, Insights (docs/PLAN.md §6.5). */
@Composable
fun BudgetBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    NavigationBar {
        bottomBarItems.forEach { item ->
            val selected = destination?.hierarchy?.any { it.hasRoute(item.route::class) } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        // A tab always opens its own screen, with only Home beneath it so Back
                        // returns to Home and the back stack never piles up. Sub-screens left open
                        // (an account page, Regular costs, ...) are not brought back: the routes are
                        // flat, so a restored sub-screen would leave no tab highlighted and tapping
                        // the tab again would restore the same sub-screen instead of the tab.
                        navController.navigate(item.route) {
                            popUpTo<Route.Home>()
                            launchSingleTop = true
                        }
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}
