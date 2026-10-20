package io.github.matteopi.budgeting.ui.planner.today

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.planner.components.PlannerTabTopBar
import io.github.matteopi.budgeting.ui.planner.components.TaskRow
import kotlinx.coroutines.launch

/**
 * The Planner's first tab (docs/PLAN.md §12.2). For now: what is overdue and due today, ticked off with *Undo*. The
 * day's timeline, the day record and habits join it in later builds.
 */
@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenTodo: () -> Unit,
    onAddTask: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.completed.collect { undo ->
            scope.launch {
                val result = snackbarHostState.showSnackbar("Done: ${undo.task.title}", actionLabel = "Undo")
                if (result == SnackbarResult.ActionPerformed) viewModel.undo(undo)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { PlannerTabTopBar(title = state.title, onOpenSettings = onOpenSettings) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddTask,
                // Material clears the extended button's text from accessibility, so the icon carries its name.
                icon = { Icon(Icons.Default.Add, contentDescription = "Add task") },
                text = { Text("Add task") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            if (state.loading) return@LazyColumn
            if (state.overdue.isNotEmpty()) {
                item(key = "overdue") { Header("Overdue") }
                items(state.overdue, key = { "task-${it.id}" }) { row ->
                    TaskRow(row = row, onToggle = { if (it) viewModel.complete(row.id) }, onOpen = { onOpenTask(row.id) })
                }
            }
            item(key = "today") { Header("Due today") }
            if (state.dueToday.isEmpty()) {
                item(key = "empty") { EmptyState("Nothing due today.") }
            }
            items(state.dueToday, key = { "task-${it.id}" }) { row ->
                TaskRow(row = row, onToggle = { if (it) viewModel.complete(row.id) }, onOpen = { onOpenTask(row.id) })
            }
            item(key = "todo") { TextButton(onClick = onOpenTodo) { Text("All tasks") } }
            item(key = "coming") {
                SectionCard(title = "Coming next", modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        "Your day's timeline, the day record with its end-of-day review, habits and free time arrive in " +
                            "the next builds of the Planner.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}
