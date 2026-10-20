package io.github.matteopi.budgeting.ui.planner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.day.DayMode
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The day (docs/PLAN.md §12.8, §12.11): where a day of the record starts and ends, and the wake and sleep times,
 * the same every day or by weekday. Changes apply at once and never move or lose a chunk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlannerSettingsViewModel = viewModel(factory = PlannerSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val day = state.day
    val sameEveryDay = day.wakeMinutes.toSet().size == 1 && day.sleepMinutes.toSet().size == 1
    var byWeekday by remember { mutableStateOf(false) }
    val showWeekdays = byWeekday || !sameEveryDay

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("The day") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) return@Column
            Text("Where a day of the record starts and ends", style = MaterialTheme.typography.titleMedium)
            ModeRow(
                selected = day.mode == DayMode.WAKE_SLEEP,
                title = "From waking to sleep",
                detail = "Sleep isn't recorded. A late night still counts until you sleep.",
            ) { viewModel.setDay(day.copy(mode = DayMode.WAKE_SLEEP)) }
            ModeRow(
                selected = day.mode == DayMode.FIXED,
                title = "A fixed cut-off",
                detail = "Each day runs from the cut-off to the next, so after midnight still counts as the day before, sleep included.",
            ) { viewModel.setDay(day.copy(mode = DayMode.FIXED)) }
            if (day.mode == DayMode.FIXED) {
                TimeField("Cut-off", day.cutoff, { t -> if (t != null) viewModel.setDay(day.copy(cutoffMinute = minuteOf(t))) })
            }
            HorizontalDivider()

            Text("Wake and sleep", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(value = showWeekdays, role = Role.Switch) { on ->
                    byWeekday = on
                    // Back to one time for every day: Monday's.
                    if (!on) viewModel.setDay(day.copy(wakeMinutes = List(7) { day.wakeMinutes[0] }, sleepMinutes = List(7) { day.sleepMinutes[0] }))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Different times by weekday", modifier = Modifier.weight(1f))
                Switch(checked = showWeekdays, onCheckedChange = null)
            }
            if (!showWeekdays) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("Wake", day.wake(DayOfWeek.MONDAY), { t -> if (t != null) viewModel.setDay(day.copy(wakeMinutes = List(7) { minuteOf(t) })) }, Modifier.weight(1f))
                    TimeField("Sleep", day.sleep(DayOfWeek.MONDAY), { t -> if (t != null) viewModel.setDay(day.copy(sleepMinutes = List(7) { minuteOf(t) })) }, Modifier.weight(1f))
                }
            } else {
                DayOfWeek.entries.forEach { weekday ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(weekday.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), modifier = Modifier.width(40.dp))
                        TimeField(
                            "Wake",
                            day.wake(weekday),
                            { t -> if (t != null) viewModel.setDay(day.withWake(weekday, minuteOf(t))) },
                            Modifier.weight(1f),
                        )
                        TimeField(
                            "Sleep",
                            day.sleep(weekday),
                            { t -> if (t != null) viewModel.setDay(day.withSleep(weekday, minuteOf(t))) },
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            Text(
                "A sleep time before the wake time is after midnight. Free time always counts from waking to sleep.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun minuteOf(time: LocalTime) = time.hour * 60 + time.minute

private fun DaySettings.withWake(day: DayOfWeek, minute: Int) = copy(wakeMinutes = wakeMinutes.toMutableList().also { it[day.value - 1] = minute })

private fun DaySettings.withSleep(day: DayOfWeek, minute: Int) = copy(sleepMinutes = sleepMinutes.toMutableList().also { it[day.value - 1] = minute })
