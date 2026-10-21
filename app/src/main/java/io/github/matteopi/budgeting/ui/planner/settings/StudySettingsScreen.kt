package io.github.matteopi.budgeting.ui.planner.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.suggest.TimeWindow
import io.github.matteopi.budgeting.ui.planner.components.DurationField
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Test tags of the study settings. */
object StudySettingsTags {
    const val DAY_OFF_TODAY = "study-day-off-today"
    const val DAILY_CAP = "study-daily-cap"
}

/**
 * Suggested study (docs/PLAN.md §12.7): on or off, preferred and avoided times, days off, block lengths and breaks, the
 * caps, finishing early, learning from the record, revision, and modules switched off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StudySettingsViewModel = viewModel(factory = StudySettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val study = state.study
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Suggested study") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) return@Column
            SwitchLine("Suggest study time", study.on) { on -> viewModel.setStudy { it.copy(on = on) } }
            Text(
                "Suggestions fill your free time for deadlines, revision and your modules' weekly targets. Accept one to plan it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!study.on) return@Column

            HorizontalDivider()
            Text("Days off", style = MaterialTheme.typography.titleMedium)
            val today = state.today
            SwitchLine("Day off today", today in state.daysOff, Modifier.testTag(StudySettingsTags.DAY_OFF_TODAY)) { off -> viewModel.setDayOff(today, off) }
            SwitchLine("Day off tomorrow", today.plusDays(1) in state.daysOff) { off -> viewModel.setDayOff(today.plusDays(1), off) }
            state.daysOff.filter { it.isAfter(today.plusDays(1)) }.forEach { date ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Day off ${PlannerText.date(date, today)}", modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.setDayOff(date, false) }) { Icon(Icons.Default.Close, contentDescription = "Remove the day off") }
                }
            }
            Text("Every week", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in study.daysOff,
                        onClick = {
                            viewModel.setStudy {
                                val days = it.weeklyDaysOff.toSet()
                                it.copy(weeklyDaysOff = (if (day.value in days) days - day.value else days + day.value).sorted())
                            }
                        },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) },
                    )
                }
            }

            HorizontalDivider()
            Text("When", style = MaterialTheme.typography.titleMedium)
            WindowList("Preferred times", study.preferred) { list -> viewModel.setStudy { it.copy(preferred = list) } }
            WindowList("Times to avoid", study.avoided) { list -> viewModel.setStudy { it.copy(avoided = list) } }
            SwitchLine("Learn from the day record", study.learnFromRecord) { on -> viewModel.setStudy { it.copy(learnFromRecord = on) } }

            HorizontalDivider()
            Text("How much", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DurationField("Longest block", study.longestMinutes, { m -> m?.let { v -> viewModel.setStudy { it.copy(longestMinutes = v) } } }, Modifier.weight(1f))
                DurationField("Break", study.breakMinutes, { m -> viewModel.setStudy { it.copy(breakMinutes = m ?: 0) } }, Modifier.weight(1f))
            }
            DurationField("Shortest block", study.shortestMinutes, { m -> m?.let { v -> viewModel.setStudy { it.copy(shortestMinutes = v) } } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DurationField(
                    "Most a day",
                    study.dailyCapMinutes,
                    { m -> m?.let { v -> viewModel.setStudy { it.copy(dailyCapMinutes = v) } } },
                    Modifier.weight(1f),
                    tag = StudySettingsTags.DAILY_CAP,
                )
                DurationField("Most a week (optional)", study.weeklyCapMinutes, { m -> viewModel.setStudy { it.copy(weeklyCapMinutes = m) } }, Modifier.weight(1f))
            }
            NumberField("Finish deadlines this many days early", study.finishEarlyDays) { n -> viewModel.setStudy { it.copy(finishEarlyDays = n) } }

            HorizontalDivider()
            Text("Revision", style = MaterialTheme.typography.titleMedium)
            NumberField("Start revising this many days before an exam", study.revisionStartDays) { n -> viewModel.setStudy { it.copy(revisionStartDays = n) } }
            DurationField("Revision block", study.revisionBlockMinutes, { m -> m?.let { v -> viewModel.setStudy { it.copy(revisionBlockMinutes = v) } } })

            if (state.modules.isNotEmpty()) {
                HorizontalDivider()
                Text("Modules", style = MaterialTheme.typography.titleMedium)
                state.modules.forEach { module ->
                    SwitchLine("Suggest for ${module.label}", module.id !in study.offModules) { on -> viewModel.setModuleOff(module.id, !on) }
                }
            }
        }
    }
}

@Composable
internal fun SwitchLine(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.filter { it.isDigit() }.take(3).toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Times of day, each a start and an end, with remove and *Add*. */
@Composable
private fun WindowList(title: String, windows: List<TimeWindow>, onChange: (List<TimeWindow>) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    windows.forEachIndexed { index, window ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField("From", timeOf(window.startMinute), { t ->
                t?.let { onChange(windows.toMutableList().also { list -> list[index] = window.copy(startMinute = it.hour * 60 + it.minute) }) }
            }, Modifier.weight(1f))
            TimeField("To", timeOf(window.endMinute), { t ->
                t?.let { onChange(windows.toMutableList().also { list -> list[index] = window.copy(endMinute = endOf(it)) }) }
            }, Modifier.weight(1f))
            IconButton(onClick = { onChange(windows.toMutableList().also { it.removeAt(index) }) }) {
                Icon(Icons.Default.Close, contentDescription = "Remove ${PlannerText.range(timeOf(window.startMinute), timeOf(window.endMinute))}")
            }
        }
    }
    TextButton(onClick = { onChange(windows + TimeWindow(9 * 60, 12 * 60)) }) { Text("Add a time") }
}

private fun timeOf(minute: Int): LocalTime = if (minute >= 24 * 60) LocalTime.MIDNIGHT else LocalTime.of(minute / 60, minute % 60)

/** 00:00 as an end is midnight at the end of the day. */
private fun endOf(time: LocalTime): Int = if (time == LocalTime.MIDNIGHT) 24 * 60 else time.hour * 60 + time.minute
