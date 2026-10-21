package io.github.matteopi.budgeting.ui.planner.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.planner.components.ColourDot

/** One habit's page (docs/PLAN.md §12.9): the month as a calendar, rates, streaks, recent weeks, *Pause* and *Archive*. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDetailScreen(
    habitId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HabitDetailViewModel = viewModel(factory = HabitDetailViewModel.factory(habitId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.name.ifEmpty { "Habit" }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (!state.missing && !state.loading) {
                        IconButton(onClick = { onEdit(habitId) }) { Icon(Icons.Default.Edit, contentDescription = "Edit habit") }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) return@Scaffold
        if (state.missing) {
            EmptyState("This habit isn't there any more.", modifier = Modifier.padding(innerPadding).fillMaxSize())
            return@Scaffold
        }
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColourDot(state.colorArgb, 14)
                Text(state.goal, style = MaterialTheme.typography.titleMedium)
            }
            Text(state.progress, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(HabitTags.PROGRESS))
            state.streak?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(HabitTags.STREAK)) }

            state.month?.let { month ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.showMonth(-1) }, enabled = state.canGoBack) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(month.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.showMonth(1) }, enabled = state.canGoForward) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }
                MonthGrid(month)
            }

            Text("How it's going", style = MaterialTheme.typography.labelLarge)
            state.rates.forEach { (label, value) ->
                Row {
                    Text(label, modifier = Modifier.weight(1f))
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (state.weeks.isNotEmpty()) {
                Text("Recent weeks", style = MaterialTheme.typography.labelLarge)
                state.weeks.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            state.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.setPaused(!state.paused) }) { Text(if (state.paused) "Resume" else "Pause") }
                OutlinedButton(onClick = { viewModel.setArchived(!state.archived) }) { Text(if (state.archived) "Unarchive" else "Archive") }
            }
        }
    }
}

/** The month, Monday first: each day's number over its mark, with its amount. */
@Composable
private fun MonthGrid(month: HabitMonthUi) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { letter ->
                Text(letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
        }
        month.weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                        if (cell.date != null) {
                            Column(
                                modifier = Modifier.fillMaxSize().testTag(HabitTags.CELL).semantics(mergeDescendants = true) { contentDescription = cell.description },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(cell.label, style = MaterialTheme.typography.labelSmall)
                                DayMark(cell.state, cell.isToday, size = 14)
                                if (cell.amount.isNotEmpty()) Text(cell.amount, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
