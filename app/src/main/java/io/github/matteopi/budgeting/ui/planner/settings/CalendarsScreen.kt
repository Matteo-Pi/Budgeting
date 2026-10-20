package io.github.matteopi.budgeting.ui.planner.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.data.calendar.CalendarInfo
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.planner.components.ColourDot
import kotlinx.coroutines.launch

/** Test tags of the Calendars screen; a calendar's rows end in its id. */
object CalendarsTags {
    const val ALLOW = "calendars-allow"
    const val SHOW = "calendars-show-"
    const val WRITE_SWITCH = "calendars-write"
    const val WRITE_TO = "calendars-write-to-"
    const val TIMETABLE_SWITCH = "calendars-timetable"
    const val REMOVE = "calendars-remove"
}

/**
 * Linking calendars (docs/PLAN.md §12.5): ticks for the calendars the Planner shows, and writing back (off by
 * default). Each permission is asked only when it is needed, with the reason above the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarsViewModel = viewModel(factory = CalendarsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // A refusal: Android may not ask again, so the app's settings are offered too.
    var refused by rememberSaveable { mutableStateOf(false) }
    // Writing back switched on, before a calendar is chosen.
    var choosing by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { !it }) refused = true
        viewModel.refresh()
    }
    val openAppSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Calendars") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) return@Column
            Text("Show in the Planner", style = MaterialTheme.typography.titleMedium)
            if (!state.canRead) {
                Text(
                    "The Planner can show your Google Calendar (and any other calendar on this phone) next to your " +
                        "timetable. It reads the copy Android keeps on the phone, so it works offline and nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR)) },
                    modifier = Modifier.testTag(CalendarsTags.ALLOW),
                ) { Text("Allow calendar access") }
                if (refused) OutlinedButton(onClick = openAppSettings) { Text("Open app settings") }
            } else if (state.calendars.isEmpty()) {
                Text(
                    "There are no calendars on this phone yet. Add your Google account in Android's settings and turn on " +
                        "calendar sync, and they will show here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.calendars.groupBy { it.accountName }.forEach { (account, calendars) ->
                    Text(account, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    calendars.forEach { calendar ->
                        val shown = calendar.id in state.readIds
                        CalendarRow(
                            calendar = calendar,
                            modifier = Modifier
                                .toggleable(value = shown, role = Role.Checkbox) { viewModel.setShown(calendar, it) }
                                .testTag(CalendarsTags.SHOW + calendar.id),
                        ) { Checkbox(checked = shown, onCheckedChange = null) }
                    }
                }
            }
            HorizontalDivider()

            Text("Write back", style = MaterialTheme.typography.titleMedium)
            val writing = state.settings.writeTo != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = writing || choosing, role = Role.Switch) { on ->
                        if (on) {
                            choosing = true
                            if (!state.canWrite) launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        } else {
                            choosing = false
                            viewModel.setWriteTo(null)
                        }
                    }
                    .testTag(CalendarsTags.WRITE_SWITCH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Add my tasks to a calendar")
                    Text(
                        "Tasks with a time go into the calendar you choose. Android sends them to Google the next time the " +
                            "phone is online. Turning this off keeps what was added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = writing || choosing, onCheckedChange = null)
            }
            if (writing || choosing) {
                when {
                    !state.canWrite -> {
                        Text("The app needs permission to add events to your calendars.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        }) { Text("Allow adding events") }
                        if (refused) OutlinedButton(onClick = openAppSettings) { Text("Open app settings") }
                    }
                    state.writable.isEmpty() -> Text("None of this phone's calendars can take new events.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text("Write to", style = MaterialTheme.typography.labelLarge)
                        if (writing && state.writeId == null) {
                            Text(
                                "${state.settings.writeTo?.name} isn't on this phone any more: choose another calendar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        state.writable.forEach { calendar ->
                            val chosen = calendar.id == state.writeId
                            CalendarRow(
                                calendar = calendar,
                                modifier = Modifier
                                    .selectable(selected = chosen, role = Role.RadioButton) {
                                        choosing = false
                                        viewModel.setWriteTo(calendar)
                                    }
                                    .testTag(CalendarsTags.WRITE_TO + calendar.id),
                            ) { RadioButton(selected = chosen, onClick = null) }
                        }
                    }
                }
            }
            if (writing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = state.settings.writeTimetable, role = Role.Switch) { viewModel.setWriteTimetable(it) }
                        .testTag(CalendarsTags.TIMETABLE_SWITCH),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Add the timetable's classes too", modifier = Modifier.weight(1f))
                    Switch(checked = state.settings.writeTimetable, onCheckedChange = null)
                }
            }
            if (state.canWrite) {
                OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.testTag(CalendarsTags.REMOVE)) {
                    Text("Remove what the app added")
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove what the app added?",
            text = "Every event the Planner added to your calendars is deleted, past ones included. Your own events stay.",
            onConfirm = {
                confirmRemove = false
                viewModel.removeAll { n ->
                    scope.launch { snackbarHostState.showSnackbar(if (n == 1) "Removed 1 event" else "Removed $n events") }
                }
            },
            onDismiss = { confirmRemove = false },
            confirmLabel = "Remove",
        )
    }
}

@Composable
private fun CalendarRow(calendar: CalendarInfo, modifier: Modifier, control: @Composable () -> Unit) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        control()
        Spacer(Modifier.width(8.dp))
        calendar.colorArgb?.let { ColourDot(it) }
        Spacer(Modifier.width(8.dp))
        Text(calendar.name, modifier = Modifier.weight(1f))
    }
}
