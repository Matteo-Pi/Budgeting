package io.github.matteopi.budgeting.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.matteopi.budgeting.ui.plan.TermDatesScreen
import io.github.matteopi.budgeting.ui.planner.components.PlannerComingSoonScreen
import io.github.matteopi.budgeting.ui.planner.day.DayScreen
import io.github.matteopi.budgeting.ui.planner.settings.CalendarsScreen
import io.github.matteopi.budgeting.ui.planner.settings.CategoriesScreen
import io.github.matteopi.budgeting.ui.planner.settings.DaySettingsScreen
import io.github.matteopi.budgeting.ui.planner.settings.ModulesScreen
import io.github.matteopi.budgeting.ui.planner.settings.PlannerSettingsScreen
import io.github.matteopi.budgeting.ui.planner.settings.TemplatesScreen
import io.github.matteopi.budgeting.ui.planner.task.TaskDetailScreen
import io.github.matteopi.budgeting.ui.planner.task.TaskEditScreen
import io.github.matteopi.budgeting.ui.planner.timetable.EntryEditScreen
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableScreen
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
            onOpenDay = { date, review -> navController.navigate(Route.Day(epochDay = date.toEpochDay(), review = review)) },
            onAddTask = { navController.navigate(Route.TaskEdit()) },
        )
    }
    composable<Route.Timetable> {
        TimetableScreen(
            onOpenSettings = openSettings,
            onOpenCalendars = { navController.navigate(Route.PlannerCalendars) },
            onAddEntry = { navController.navigate(Route.EntryEdit()) },
            onEditEntry = { id -> navController.navigate(Route.EntryEdit(entryId = id)) },
            onOpenTask = openTask,
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
            onOpenTermDates = { navController.navigate(Route.PlannerTermDates) },
            onOpenCalendars = { navController.navigate(Route.PlannerCalendars) },
            onOpenDaySettings = { navController.navigate(Route.PlannerDaySettings) },
            onOpenCategories = { navController.navigate(Route.PlannerCategories) },
        )
    }
    composable<Route.Modules> {
        ModulesScreen(onBack = { navController.popBackStack() })
    }
    composable<Route.Templates> {
        TemplatesScreen(onBack = { navController.popBackStack() })
    }
    composable<Route.EntryEdit> { backStackEntry ->
        val args = backStackEntry.toRoute<Route.EntryEdit>()
        EntryEditScreen(
            entryId = args.entryId,
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }
    composable<Route.PlannerCalendars> {
        CalendarsScreen(onBack = { navController.popBackStack() })
    }
    composable<Route.PlannerTermDates> {
        TermDatesScreen(onBack = { navController.popBackStack() })
    }
    composable<Route.Day> { backStackEntry ->
        val args = backStackEntry.toRoute<Route.Day>()
        DayScreen(epochDay = args.epochDay, review = args.review, onBack = { navController.popBackStack() })
    }
    composable<Route.PlannerDaySettings> {
        DaySettingsScreen(onBack = { navController.popBackStack() })
    }
    composable<Route.PlannerCategories> {
        CategoriesScreen(onBack = { navController.popBackStack() })
    }
}
