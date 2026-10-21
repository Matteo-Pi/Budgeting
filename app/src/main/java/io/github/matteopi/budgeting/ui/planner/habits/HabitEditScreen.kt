package io.github.matteopi.budgeting.ui.planner.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.habits.HabitField
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.planner.components.DurationField
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import io.github.matteopi.budgeting.ui.planner.settings.MODULE_COLOURS
import io.github.matteopi.budgeting.ui.planner.task.ChipRow
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Adds or edits a habit (docs/PLAN.md §12.9): its name and colour, the goal (chosen days, times a week, at least or at
 * most an amount), how it is counted, streaks, a reminder and *Term time only*. A plain scrolling column, so every
 * field stays composed (docs/PLAN.md §8); *Save* is off until the habit can be saved, and the reason is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitEditScreen(
    habitId: Long?,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HabitEditViewModel = viewModel(factory = HabitEditViewModel.factory(habitId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "Add habit" else "Edit habit") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { innerPadding ->
        if (state.loading) return@Scaffold
        val errors = form.errors
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { value -> viewModel.update { it.copy(name = value) } },
                label = { Text("Name") },
                placeholder = { Text("e.g. Gym") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(HabitTags.NAME),
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODULE_COLOURS.forEach { argb ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(argb.toInt()))
                            .border(if (argb == form.colorArgb) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable(role = Role.RadioButton) { viewModel.update { it.copy(colorArgb = argb) } },
                    )
                }
            }

            Text("Goal", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = GoalKind.entries,
                selected = form.kind,
                label = ::kindLabel,
                onSelect = { kind -> viewModel.update { it.withKind(kind) } },
            )
            if (form.needsTarget) TargetField(form, viewModel)
            if (form.kind == GoalKind.AT_LEAST || form.kind == GoalKind.AT_MOST) {
                ChipRow(
                    options = GoalUnit.entries,
                    selected = form.unit,
                    label = { unit -> if (unit == GoalUnit.MINUTES) "Minutes" else if (unit == GoalUnit.TIMES) "Times" else "A number" },
                    onSelect = { unit -> viewModel.update { it.copy(unit = unit) } },
                )
                ChipRow(
                    options = GoalPeriod.entries,
                    selected = form.period,
                    label = { if (it == GoalPeriod.DAY) "A day" else "A week" },
                    onSelect = { period -> viewModel.update { it.copy(period = period) } },
                )
            }
            if (form.usesDays) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in form.days,
                            onClick = { viewModel.update { if (day in it.days) it.copy(days = it.days - day) else it.copy(days = it.days + day) } },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) },
                        )
                    }
                }
            }

            Text("Counting", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = HabitCounting.entries,
                selected = form.counting,
                label = { counting ->
                    when (counting) {
                        HabitCounting.MANUAL -> "By hand"
                        HabitCounting.RECORD -> "From the day record"
                        HabitCounting.BOTH -> "Both"
                    }
                },
                onSelect = { counting -> viewModel.update { it.copy(counting = counting) } },
            )
            if (form.counting != HabitCounting.MANUAL) {
                Text("The category that counts", style = MaterialTheme.typography.labelMedium)
                ChipRow(
                    options = listOf<Long?>(null) + state.categories.map { it.id },
                    selected = form.categoryId,
                    label = { id -> if (id == null) "None" else state.categories.first { it.id == id }.name },
                    onSelect = { id -> viewModel.update { it.copy(categoryId = id) } },
                )
                if (form.usesMinMinutes) {
                    DurationField(
                        label = "A session counts from (optional)",
                        minutes = form.minMinutes,
                        onMinutesChange = { minutes -> viewModel.update { it.copy(minMinutes = minutes) } },
                        tag = HabitTags.MIN_MINUTES,
                    )
                }
            }

            Text("More", style = MaterialTheme.typography.labelLarge)
            SwitchRow("Streaks", form.streaks) { on -> viewModel.update { it.copy(streaks = on) } }
            SwitchRow("Term time only", form.termOnly) { on -> viewModel.update { it.copy(termOnly = on) } }
            TimeField(
                label = "Reminder (optional)",
                time = form.reminderTime,
                onTimeChange = { time -> viewModel.update { it.copy(reminderTime = time) } },
                tag = HabitTags.REMINDER,
            )
            DateField(label = "Starts", date = form.startDate, onDateChange = { date -> viewModel.update { it.copy(startDate = date) } })

            val firstError = HabitField.entries.firstNotNullOfOrNull { errors[it] }
            if (firstError != null) Text(firstError, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = viewModel::save,
                enabled = errors.isEmpty(),
                modifier = Modifier.fillMaxWidth().testTag(HabitTags.SAVE),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun TargetField(form: HabitForm, viewModel: HabitEditViewModel) {
    if (form.kind != GoalKind.TIMES_PER_WEEK && form.unit == GoalUnit.MINUTES) {
        DurationField(
            label = if (form.kind == GoalKind.AT_MOST) "At most" else "How long",
            minutes = form.target,
            onMinutesChange = { minutes -> viewModel.update { it.copy(target = minutes) } },
            tag = HabitTags.TARGET,
        )
    } else {
        OutlinedTextField(
            value = form.target?.toString() ?: "",
            onValueChange = { value -> viewModel.update { it.copy(target = value.filter { c -> c.isDigit() }.take(5).toIntOrNull()) } },
            label = {
                Text(
                    when (form.kind) {
                        GoalKind.TIMES_PER_WEEK -> "Times a week"
                        GoalKind.AT_MOST -> "At most"
                        else -> "How many"
                    },
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(HabitTags.TARGET),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

fun kindLabel(kind: GoalKind): String = when (kind) {
    GoalKind.ON_DAYS -> "On chosen days"
    GoalKind.TIMES_PER_WEEK -> "Times a week"
    GoalKind.AT_LEAST -> "At least"
    GoalKind.AT_MOST -> "At most (a limit)"
}
