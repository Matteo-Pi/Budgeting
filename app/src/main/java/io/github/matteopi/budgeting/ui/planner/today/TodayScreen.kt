package io.github.matteopi.budgeting.ui.planner.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.planner.components.PlannerTabTopBar
import io.github.matteopi.budgeting.ui.planner.components.TaskRow
import io.github.matteopi.budgeting.ui.planner.timetable.ItemKind
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableItemUi
import kotlinx.coroutines.launch

/** Test tags of the Today tab. */
object TodayTags {
    const val ON_TODAY = "today-on"
}

/**
 * The Planner's first tab (docs/PLAN.md §12.2). For now: today's classes and calendar events, and what is overdue and
 * due today, ticked off with *Undo*. The day's timeline, the day record and habits join it in later builds.
 */
@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenTodo: () -> Unit,
    onOpenTimetable: () -> Unit,
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
            if (state.onToday.isNotEmpty()) {
                item(key = "on") { Header("On today") }
                items(state.onToday, key = { it.key }) { item -> OnTodayRow(item, onClick = onOpenTimetable) }
            }
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
                        "The day record with its end-of-day review, habits and free time arrive in the next builds of " +
                            "the Planner.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** "09:00–10:00  EC101 Lecture · Lecture · 2.14", with a bar in its colour; a cancelled class is struck through. */
@Composable
private fun OnTodayRow(item: TimetableItemUi, onClick: () -> Unit) {
    val cancelled = item.kind == ItemKind.CANCELLED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(TodayTags.ON_TODAY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .background(item.colorArgb?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(item.timeText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
            if (item.detail.isNotBlank()) {
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
