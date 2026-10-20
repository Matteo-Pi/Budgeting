package io.github.matteopi.budgeting.ui.planner.task

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.todo.TaskField
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.OptionalDateField
import io.github.matteopi.budgeting.ui.planner.components.DurationField
import io.github.matteopi.budgeting.ui.planner.components.ModulePicker
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import io.github.matteopi.budgeting.ui.planner.todo.TodoMapping
import io.github.matteopi.budgeting.ui.plan.ChoiceRow
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Test tags of the task editor's fields. */
object TaskEditTags {
    const val TITLE = "task-edit-title"
    const val TIME = "task-edit-time"
    const val ESTIMATE = "task-edit-estimate"
    const val LENGTH = "task-edit-length"
    const val INTERVAL = "task-edit-interval"
    const val SAVE = "task-edit-save"
}

/**
 * Adds or edits a task, deadline or step (docs/PLAN.md §12.3). A plain scrolling column, not a lazy list, so every
 * field stays composed (docs/PLAN.md §8). *Save* is off until the task can be saved, and the reason is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    taskId: Long?,
    parentId: Long?,
    kind: String?,
    text: String?,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskEditViewModel = viewModel(factory = TaskEditViewModel.factory(taskId, parentId, kind, text)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form

    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }

    val title = when {
        form.isStep && state.isNew -> "Add step"
        form.isStep -> "Edit step"
        state.isNew && form.isDeadline -> "Add deadline"
        state.isNew -> "Add task"
        form.isDeadline -> "Edit deadline"
        else -> "Edit task"
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = viewModel::requestDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) return@Scaffold
        val errors = form.errors
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.parentTitle != null) {
                Text("Step of ${state.parentTitle}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = form.title,
                onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(TaskEditTags.TITLE),
            )
            if (!form.isStep) {
                ChoiceRow(
                    options = listOf(TaskKind.TASK, TaskKind.DEADLINE),
                    selected = form.kind,
                    label = { if (it == TaskKind.TASK) "Task" else "Deadline" },
                    onSelect = { choice -> viewModel.update { it.copy(kind = choice) } },
                )
            }
            if (form.isDeadline && !form.isStep) {
                ChipRow(
                    options = DeadlineType.entries,
                    selected = form.deadlineType,
                    label = ::deadlineTypeLabel,
                    onSelect = { choice -> viewModel.update { it.copy(deadlineType = choice) } },
                )
            }

            Text("When", style = MaterialTheme.typography.labelLarge)
            OptionalDateField(
                label = if (form.isDeadline) "Due date" else "Date",
                date = form.dueDate,
                onDateChange = { date -> viewModel.update { it.copy(dueDate = date) } },
                initialDate = state.today,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Not "Today": that is the name of the Planner's first tab, in the bar below.
                TextButton(onClick = { viewModel.update { it.copy(dueDate = state.today) } }) { Text("Due today") }
                TextButton(onClick = { viewModel.update { it.copy(dueDate = state.today.plusDays(1)) } }) { Text("Due tomorrow") }
                if (form.dueDate != null) {
                    TextButton(onClick = { viewModel.update { it.copy(dueDate = null, dueTime = null, reminderMinutesBefore = null) } }) {
                        Text("No date")
                    }
                }
            }
            TimeField(
                label = if (form.isExam) "Start time" else "Time (optional)",
                time = form.dueTime,
                onTimeChange = { time -> viewModel.update { it.copy(dueTime = time) } },
                tag = TaskEditTags.TIME,
            )
            if (form.isExam) {
                DurationField(
                    label = "Length",
                    minutes = form.lengthMinutes,
                    onMinutesChange = { minutes -> viewModel.update { it.copy(lengthMinutes = minutes) } },
                    tag = TaskEditTags.LENGTH,
                )
            }
            DurationField(
                label = if (form.isDeadline) "Work it needs (estimate)" else "Estimate (optional)",
                minutes = form.estimateMinutes,
                onMinutesChange = { minutes -> viewModel.update { it.copy(estimateMinutes = minutes) } },
                tag = TaskEditTags.ESTIMATE,
            )

            Text("Details", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = Priority.entries,
                selected = form.priority,
                label = { if (it == Priority.NONE) "No priority" else TodoMapping.priorityLabel(it).removeSuffix(" priority") },
                onSelect = { choice -> viewModel.update { it.copy(priority = choice) } },
            )
            ModulePicker(
                modules = state.modules,
                selectedId = form.moduleId,
                onSelect = { id -> viewModel.update { it.copy(moduleId = id) } },
            )
            OutlinedTextField(
                value = form.tagsText,
                onValueChange = { value -> viewModel.update { it.copy(tagsText = value) } },
                label = { Text("Tags (separated by commas)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.location,
                onValueChange = { value -> viewModel.update { it.copy(location = value) } },
                label = { Text(if (form.isExam) "Room" else "Place (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (form.canRepeat) RepeatSection(form, viewModel)

            if (form.dueDate != null) {
                Text("Reminder", style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    options = form.reminderChoices,
                    selected = form.reminderMinutesBefore,
                    label = { minutes -> if (minutes == null) "None" else PlannerText.reminder(minutes, timed = form.dueTime != null) },
                    onSelect = { choice -> viewModel.update { it.copy(reminderMinutesBefore = choice) } },
                )
            }

            ChecklistEditor(form.checklist) { items -> viewModel.update { it.copy(checklist = items) } }

            OutlinedTextField(
                value = form.notes,
                onValueChange = { value -> viewModel.update { it.copy(notes = value) } },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            val firstError = TaskField.entries.firstNotNullOfOrNull { errors[it] }
            if (firstError != null) Text(firstError, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = viewModel::save,
                enabled = errors.isEmpty(),
                modifier = Modifier.fillMaxWidth().testTag(TaskEditTags.SAVE),
            ) { Text("Save") }
        }
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = if (form.isDeadline) "Delete this deadline?" else "Delete this task?",
            text = if (form.isDeadline) "\"${form.title}\" and its steps will be removed." else "\"${form.title}\" will be removed.",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            confirmLabel = "Delete",
        )
    }
}

@Composable
private fun RepeatSection(form: TaskForm, viewModel: TaskEditViewModel) {
    Text("Repeat", style = MaterialTheme.typography.labelLarge)
    ChipRow(
        options = listOf(null, RepeatKind.DAYS, RepeatKind.WEEKS, RepeatKind.WEEKDAYS),
        selected = form.repeatKind,
        label = { kind ->
            when (kind) {
                null -> "Never"
                RepeatKind.DAYS -> "Every N days"
                RepeatKind.WEEKS -> "Every N weeks"
                RepeatKind.WEEKDAYS -> "On chosen days"
            }
        },
        onSelect = { choice ->
            viewModel.update {
                it.copy(
                    repeatKind = choice,
                    repeatDays = if (choice == RepeatKind.WEEKDAYS && it.repeatDays.isEmpty()) setOfNotNull(it.dueDate?.dayOfWeek) else it.repeatDays,
                )
            }
        },
    )
    when (form.repeatKind) {
        RepeatKind.DAYS, RepeatKind.WEEKS -> OutlinedTextField(
            value = form.repeatInterval.toString(),
            onValueChange = { value ->
                value.filter { it.isDigit() }.take(3).toIntOrNull()?.let { n -> viewModel.update { it.copy(repeatInterval = n) } }
            },
            label = { Text(if (form.repeatKind == RepeatKind.DAYS) "Every how many days" else "Every how many weeks") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(TaskEditTags.INTERVAL),
        )
        RepeatKind.WEEKDAYS -> Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DayOfWeek.entries.forEach { day ->
                FilterChip(
                    selected = day in form.repeatDays,
                    onClick = {
                        viewModel.update { if (day in it.repeatDays) it.copy(repeatDays = it.repeatDays - day) else it.copy(repeatDays = it.repeatDays + day) }
                    },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) },
                )
            }
        }
        null -> Unit
    }
}

/** The checklist: a tick box and a text field per line, with remove and *Add item*. */
@Composable
private fun ChecklistEditor(items: List<ChecklistItem>, onChange: (List<ChecklistItem>) -> Unit) {
    Text("Checklist", style = MaterialTheme.typography.labelLarge)
    items.forEachIndexed { index, item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.done,
                onCheckedChange = { done -> onChange(items.toMutableList().also { it[index] = item.copy(done = done) }) },
            )
            OutlinedTextField(
                value = item.text,
                onValueChange = { text -> onChange(items.toMutableList().also { it[index] = item.copy(text = text) }) },
                singleLine = true,
                label = { Text("Item ${index + 1}") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(items.toMutableList().also { it.removeAt(index) }) }) {
                Icon(Icons.Default.Close, contentDescription = "Remove item ${index + 1}")
            }
        }
    }
    TextButton(onClick = { onChange(items + ChecklistItem("")) }) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text("Add item", modifier = Modifier.padding(start = 4.dp))
    }
}

/** A sideways row of chips for a small choice; the selected one is filled. */
@Composable
internal fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

fun deadlineTypeLabel(type: DeadlineType): String = when (type) {
    DeadlineType.ASSIGNMENT -> "Assignment"
    DeadlineType.EXAM -> "Exam"
    DeadlineType.APPLICATION -> "Application"
    DeadlineType.OTHER -> "Other"
}
