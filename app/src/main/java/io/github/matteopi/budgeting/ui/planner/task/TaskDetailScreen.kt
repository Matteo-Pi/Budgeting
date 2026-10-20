package io.github.matteopi.budgeting.ui.planner.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.TaskRules
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.planner.components.PlannerTags
import io.github.matteopi.budgeting.ui.planner.components.TaskRow

/**
 * One task's page (docs/PLAN.md §12.3): its details and checklist; for a deadline, where it stands and its steps,
 * with *Add steps from a template*. A plain scrolling column so every part stays composed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    onAddStep: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskDetailViewModel = viewModel(factory = TaskDetailViewModel.factory(taskId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val task = state.task

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            task == null -> ""
                            TaskRules.isExam(task) -> "Exam"
                            task.isDeadline -> "Deadline"
                            task.isStep -> "Step"
                            else -> "Task"
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (task != null) IconButton(onClick = { onEdit(task.id) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) return@Scaffold
        if (task == null) {
            EmptyState("This task has been deleted.", modifier = Modifier.padding(innerPadding))
            return@Scaffold
        }
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.headlineSmall,
                textDecoration = if (task.isOpen) null else TextDecoration.LineThrough,
            )
            val parentId = task.parentId
            if (state.parentTitle != null && parentId != null) {
                TextButton(onClick = { onOpenTask(parentId) }) { Text("Step of ${state.parentTitle}") }
            }
            state.details.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            state.deadline?.let { card ->
                SectionCard(title = card.headline, modifier = Modifier.testTag(PlannerTags.DEADLINE_STATUS)) {
                    card.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            if (task.checklist.isNotEmpty()) {
                Text("Checklist", style = MaterialTheme.typography.titleSmall)
                task.checklist.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.done, onCheckedChange = { done -> viewModel.setChecklistItem(index, done) })
                        Text(item.text, textDecoration = if (item.done) TextDecoration.LineThrough else null)
                    }
                }
            }

            if (task.isDeadline) {
                Text("Steps", style = MaterialTheme.typography.titleSmall)
                if (state.steps.isEmpty()) {
                    Text(
                        "Split it into steps, such as research, draft and edit, each with its own date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.steps.forEach { step ->
                    TaskRow(
                        row = step,
                        onToggle = { done -> viewModel.setStepDone(step.id, done) },
                        onOpen = { onOpenTask(step.id) },
                        modifier = Modifier.testTag(PlannerTags.STEP_ROW),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAddStep(task.id) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Add step", modifier = Modifier.padding(start = 4.dp))
                    }
                    TemplateMenu(state) { viewModel.applyTemplate(it) }
                }
            }

            if (task.notes.isNotBlank()) {
                Text("Notes", style = MaterialTheme.typography.titleSmall)
                Text(task.notes, style = MaterialTheme.typography.bodyMedium)
            }

            Button(onClick = viewModel::complete, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        !task.isOpen -> "Mark as not done"
                        task.repeat != null -> "Done (moves to the next date)"
                        else -> "Mark as done"
                    },
                )
            }
        }
    }
}

@Composable
private fun TemplateMenu(state: TaskDetailUiState, onPick: (BreakdownTemplate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("From a template") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.templates.forEach { template ->
                DropdownMenuItem(
                    text = { Text(template.name) },
                    onClick = {
                        open = false
                        onPick(template)
                    },
                )
            }
        }
    }
}
