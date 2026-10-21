package io.github.matteopi.budgeting.ui.planner.habits

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import io.github.matteopi.budgeting.ui.planner.components.PlannerTabTopBar
import kotlinx.coroutines.launch

/**
 * The Habits tab (docs/PLAN.md §12.9): the habits that count today, ticked, added to or skipped with *Undo*, with this
 * week's progress and the streak; then the others and the archived ones. A tap opens the habit's page.
 */
@Composable
fun HabitsScreen(
    onOpenSettings: () -> Unit,
    onOpenHabit: (Long) -> Unit,
    onAddHabit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HabitsViewModel = viewModel(factory = HabitsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            scope.launch {
                val result = snackbarHostState.showSnackbar(event.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) viewModel.undo(event)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { PlannerTabTopBar(title = "Habits", onOpenSettings = onOpenSettings) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddHabit,
                // Material clears the extended button's text from accessibility, so the icon carries its name.
                icon = { Icon(Icons.Default.Add, contentDescription = "Add habit") },
                text = { Text("Add habit") },
            )
        },
    ) { innerPadding ->
        if (state.loading) return@Scaffold
        if (state.isEmpty) {
            EmptyState(
                "No habits yet. Add one, such as \"Gym 3 times a week\", \"Read 20 minutes a day\" or \"Phone under 1 hour a day\".",
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
        ) {
            section("Today's habits", state.sections.today, viewModel, onOpenHabit)
            section("Other habits", state.sections.other, viewModel, onOpenHabit)
            section("Archived", state.sections.archived, viewModel, onOpenHabit)
        }
    }
}

private fun LazyListScope.section(title: String, rows: List<HabitRowUi>, viewModel: HabitsViewModel, onOpenHabit: (Long) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "header-$title") {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    }
    items(rows, key = { "habit-$title-${it.id}" }) { row ->
        HabitRow(
            row = row,
            onOpen = { onOpenHabit(row.id) },
            onToggle = { viewModel.toggle(row.id) },
            onAdd = { amount -> viewModel.add(row.id, amount) },
            onSkip = { skipped -> viewModel.skip(row.id, skipped) },
        )
    }
}
