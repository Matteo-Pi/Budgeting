package io.github.matteopi.budgeting.ui.planner.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.data.settings.PlannerCalendarSettings
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import io.github.matteopi.budgeting.work.planner.PlannerNotifications

/**
 * The Planner's settings (docs/PLAN.md §12.11), opened from the gear on every Planner tab: modules, breakdown
 * templates, term dates, calendars and reminders; later builds add the day, the day record and study suggestions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerSettingsScreen(
    onBack: () -> Unit,
    onOpenModules: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenTermDates: () -> Unit,
    onOpenCalendars: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlannerSettingsViewModel = viewModel(factory = PlannerSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-read the permissions whenever the screen comes back (they can change in Android's settings).
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val canNotify = remember(resumes) { PlannerNotifications.canPost(context) }
    val exact = remember(resumes) { viewModel.exactAlarmsAllowed() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Planner settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Your work", style = MaterialTheme.typography.titleMedium)
            LinkRow(
                title = "Modules",
                detail = when (val n = state.modules.count { !it.archived }) {
                    0 -> "None yet. Add your modules to group tasks and, later, plan study for them."
                    1 -> "1 module"
                    else -> "$n modules"
                },
                onClick = onOpenModules,
            )
            LinkRow(
                title = "Breakdown templates",
                detail = state.templates.joinToString(", ") { it.name },
                onClick = onOpenTemplates,
            )
            HorizontalDivider()

            Text("Timetable", style = MaterialTheme.typography.titleMedium)
            LinkRow(
                title = "Term dates",
                detail = when (state.terms.size) {
                    0 -> "None yet. Add them so classes can follow teaching weeks and skip the holidays."
                    else -> state.terms.sortedBy { it.startDate }.joinToString(", ") { it.name } + " (shared with Budgeting)"
                },
                onClick = onOpenTermDates,
            )
            LinkRow(
                title = "Calendars",
                detail = calendarsSummary(state.calendars),
                onClick = onOpenCalendars,
            )
            HorizontalDivider()

            Text("Reminders", style = MaterialTheme.typography.titleMedium)
            if (!state.loading) {
                TimeField(
                    label = "Reminders for tasks without a time",
                    time = state.reminders.taskTime,
                    onTimeChange = { time -> if (time != null) viewModel.setTaskReminderTime(time) },
                )
            }
            if (!canNotify) {
                Text(
                    "Notifications are off for this app, so reminders can't show.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(appSettingsIntent(context.packageName))
                    }
                }) { Text("Allow notifications") }
            }
            if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Text(
                    "Reminders may be a few minutes late: Android isn't letting the app set exact alarms.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("Allow exact alarms") }
            }
            Text(
                "Each task can have its own reminder: set it when you add or edit the task.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun calendarsSummary(settings: PlannerCalendarSettings): String {
    val read = when (settings.read.size) {
        0 -> "No calendars linked"
        1 -> "Showing ${settings.read.single().name}"
        else -> "Showing ${settings.read.size} calendars"
    }
    val write = settings.writeTo?.let { " · writing to ${it.name}" } ?: ""
    return read + write
}

private fun appSettingsIntent(packageName: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

@Composable
private fun LinkRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
