package io.github.matteopi.budgeting.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.matteopi.budgeting.ui.planner.components.PlannerComingSoonScreen
import io.github.matteopi.budgeting.ui.planner.settings.ModulesScreen
import io.github.matteopi.budgeting.ui.planner.settings.PlannerSettingsScreen
import io.github.matteopi.budgeting.ui.planner.settings.TemplatesScreen
import io.github.matteopi.budgeting.ui.planner.task.TaskDetailScreen
import io.github.matteopi.budgeting.ui.planner.task.TaskEditScreen
import io.github.matteopi.budgeting.ui.planner.today.TodayScreen
import io.github.matteopi.budgeting.ui.planner.todo.TodoScreen

/** The Planner section's destinations (docs/PLAN.md §13.8), kept here so `BudgetNavHost` stays readable. */
fun NavGraphBuilder.plannerDestinations(navController: NavHostController) {
    val openSettings = { navController.navigate(Route.PlannerSettings) }
    val openTask = { id: Long -> navController.navigate(Route.TaskDetail(id)) }

    composable<Route.Today> {
        TodayScreen(
            onOpenSettings = openSettings,
            onOpenTask = openTask,
            onOpenTodo = {
                navController.navigate(Route.Todo) {
                    popUpTo<Route.Today>()
                    launchSingleTop = true
                }
            },
            onAddTask = { navController.navigate(Route.TaskEdit()) },
        )
    }
    composable<Route.Timetable> {
        PlannerComingSoonScreen(
            title = "Timetable",
            message = "Your weekly timetable, with your Google Calendar beside it, comes in the next build of the Planner.",
            onOpenSettings = openSettings,
        )
    }
    composable<Route.Todo> {
        TodoScreen(
            onOpenSettings = openSettings,
            onOpenTask = openTask,
            onEditTask = { id -> navController.navigate(Route.TaskEdit(taskId = id)) },
            onAddTask = { text -> navController.navigate(Route.TaskEdit(text = text?.takeIf { it.isNotBlank() })) },
        )
    }
    composable<Route.Habits> {
        PlannerComingSoonScreen(
            title = "Habits",
            message = "Habits, with ticks, streaks and goals such as \"gym 3 times a week\", come in a later build of the Planner.",
            onOpenSettings = openSettings,
        )
    }
    composable<Route.PlannerInsights> {
        PlannerComingSoonScreen(
            title = "Insights",
            message = "Where your time goes, study against your targets, focus and the weekly review come in a later build of the Planner.",
            onOpenSettings = openSettings,
        )
    }
    composable<Route.TaskEdit> { backStackEntry ->
        val args = backStackEntry.toRoute<Route.TaskEdit>()
        TaskEditScreen(
            taskId = args.taskId,
            parentId = args.parentId,
            kind = args.kind,
            text = args.text,
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
            onDeleted = {
                // The task's own page is gone too, so don't return to it.
                if (!navController.popBackStack<Route.TaskDetail>(inclusive = true)) navController.popBackStack()
            },
        )
    }
    composable<Route.TaskDetail> { backStackEntry ->
        val args = backStackEntry.toRoute<Route.TaskDetail>()
        TaskDetailScreen(
            taskId = args.taskId,
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(Route.TaskEdit(taskId = id)) },
            onOpenTask = openTask,
            onAddStep = { parentId -> navController.navigate(Route.TaskEdit(parentId = parentId)) },
        )
    }
    composable<Route.PlannerSettings> {
        PlannerSettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenModules = { navController.navigate(Route.Modules) },
            onOpenTemplates = { navController.navigate(Route.Templates) },
        )
    }
    composable<Route.Modules> {
        ModulesScreen(onBack = { navController.popBackStack() })
    }
    composable<Route.Templates> {
        TemplatesScreen(onBack = { navController.popBackStack() })
    }
}
