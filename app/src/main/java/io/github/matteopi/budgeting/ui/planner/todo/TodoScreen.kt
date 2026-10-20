package io.github.matteopi.budgeting.ui.planner.todo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.todo.TodoFilter
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.planner.components.PlannerTabTopBar
import io.github.matteopi.budgeting.ui.planner.components.PlannerTags
import io.github.matteopi.budgeting.ui.planner.components.TaskRow
import kotlinx.coroutines.launch

/**
 * The To-do tab (docs/PLAN.md §12.3, §13.9): the quick-add line with a preview of what it read, the filters, and
 * the tasks, each ticked off with *Undo*.
 */
@Composable
fun TodoScreen(
    onOpenSettings: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onEditTask: (Long) -> Unit,
    onAddTask: (text: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodoViewModel = viewModel(factory = TodoViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The line is held here, not read back from the view model's state (worked out off the main thread), so typing
    // never waits for it; the view model gets every change for the preview.
    var quickText by rememberSaveable { mutableStateOf("") }
    val setQuickText = { value: String ->
        quickText = value
        viewModel.setQuickText(value)
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            if (event is TodoEvent.OpenEditor || event is TodoEvent.Added) setQuickText("")
            if (event is TodoEvent.OpenEditor) {
                onAddTask(event.text)
                return@collect
            }
            scope.launch {
                val undoable = event is TodoEvent.Completed || event is TodoEvent.Deleted
                val result = snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = if (undoable) "Undo" else null,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undo(event)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { PlannerTabTopBar(title = "To-do", onOpenSettings = onOpenSettings) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAddTask(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add task") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            item(key = "quick") {
                QuickAddLine(
                    text = quickText,
                    chips = if (quickText.isBlank()) emptyList() else state.previewChips,
                    onTextChange = setQuickText,
                    onAdd = viewModel::addQuick,
                    onMore = {
                        val text = quickText
                        setQuickText("")
                        onAddTask(text)
                    },
                )
            }
            item(key = "filters") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TodoMapping.FILTERS.forEach { filter ->
                        val count = state.counts[filter]
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = {
                                Text(if (count != null && count > 0) "${TodoMapping.filterLabel(filter)} ($count)" else TodoMapping.filterLabel(filter))
                            },
                        )
                    }
                }
            }
            if (state.loading) return@LazyColumn
            if (state.filter == TodoFilter.DONE) {
                if (state.done.isEmpty()) {
                    item(key = "empty") { EmptyState(TodoMapping.emptyMessage(TodoFilter.DONE)) }
                }
                items(state.done, key = { "done-${it.completionId}" }) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag(PlannerTags.DONE_ROW).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge)
                            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { viewModel.markNotDone(row) }) { Text("Not done") }
                    }
                }
            } else {
                if (state.sections.isEmpty()) {
                    item(key = "empty") { EmptyState(TodoMapping.emptyMessage(state.filter)) }
                }
                state.sections.forEach { section ->
                    item(key = "header-${section.title}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(section.rows, key = { "task-${it.id}" }) { row ->
                        TaskRow(
                            row = row,
                            onToggle = { checked -> if (checked) viewModel.complete(row.id) },
                            onOpen = { onOpenTask(row.id) },
                            menu = listOf(
                                "Edit" to { onEditTask(row.id) },
                                "Move to tomorrow" to { viewModel.moveToTomorrow(row.id) },
                                "Delete" to { viewModel.delete(row.id) },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** One line to type a task in, with chips showing what was read, *Add* and *More* (the full editor). */
@Composable
private fun QuickAddLine(
    text: String,
    chips: List<String>,
    onTextChange: (String) -> Unit,
    onAdd: () -> Unit,
    onMore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Add a task") },
            placeholder = { Text("Essay draft fri 3pm 2h #EC101") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
            trailingIcon = {
                if (text.isNotBlank()) {
                    IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add") }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag(PlannerTags.QUICK_ADD),
        )
        if (chips.isNotEmpty() || text.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp)
                    .testTag(PlannerTags.QUICK_ADD_PREVIEW),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chips.forEach { chip -> AssistChip(onClick = {}, label = { Text(chip) }) }
                TextButton(onClick = onMore) { Text("More…") }
            }
        }
    }
}
