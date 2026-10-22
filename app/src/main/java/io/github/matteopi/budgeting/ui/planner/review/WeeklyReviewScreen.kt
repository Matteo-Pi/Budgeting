package io.github.matteopi.budgeting.ui.planner.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionRow
import kotlinx.coroutines.launch

/** Test tags of the weekly review. */
object WeeklyReviewTags {
    const val ACCEPT_ALL = "review-accept-all"
    const val MOVE_OPTION = "review-move-option"
}

/**
 * The weekly review (docs/PLAN.md §12.10, §13.9): the week planned against what happened, the categories that took
 * the most time, the habits and the deadlines of the next two weeks; then next week's suggestions by day, with
 * *Accept all* and, for each, *Accept*, *Move* and *Another time*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WeeklyReviewViewModel = viewModel(factory = WeeklyReviewViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            scope.launch {
                val result = snackbarHostState.showSnackbar(event.message, actionLabel = "Undo")
                if (result == SnackbarResult.ActionPerformed) viewModel.undo(event)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Weekly review") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val ui = state.ui
        if (state.loading || ui == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "week") {
                SectionCard(title = ui.reviewedTitle) {
                    Text(ui.studyText, style = MaterialTheme.typography.bodyLarge)
                    ui.outcomesText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (ui.top.isNotEmpty()) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Text("Most time", style = MaterialTheme.typography.labelLarge)
                        ui.top.forEach { Line(it) }
                    }
                    if (ui.habits.isNotEmpty()) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Text("Habits", style = MaterialTheme.typography.labelLarge)
                        ui.habits.forEach { Line(it) }
                    }
                }
            }
            if (ui.deadlines.isNotEmpty()) {
                item(key = "deadlines") {
                    SectionCard(title = "Deadlines in the next two weeks") {
                        ui.deadlines.forEach { row ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(row.title, style = MaterialTheme.typography.bodyMedium)
                                Text(row.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                row.riskText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            item(key = "next") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ui.planningLabel, style = MaterialTheme.typography.titleMedium)
                        Text(ui.planningTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = viewModel::acceptAll, enabled = ui.suggestionCount > 0, modifier = Modifier.testTag(WeeklyReviewTags.ACCEPT_ALL)) {
                        Text(if (ui.suggestionCount > 0) "Accept all (${ui.suggestionCount})" else "Accept all")
                    }
                }
                if (!state.suggestionsReady) {
                    Text("Working out the suggestions…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                } else if (ui.suggestionCount == 0) {
                    Text(
                        "No study to suggest: the deadlines, revision and targets are covered, or suggestions are off.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            for (day in ui.days) {
                if (day.rows.isEmpty()) continue
                item(key = "day-${day.date}") { Text(day.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(day.rows, key = { "suggestion-${it.key}" }) { row ->
                    SuggestionRow(
                        row = row,
                        onAccept = { viewModel.accept(row.key) },
                        onDismiss = { viewModel.anotherTime(row.key) },
                        onMove = { moving = row.key },
                    )
                }
            }
        }
    }

    moving?.let { key ->
        val options = remember(key, state.ui) { viewModel.moveOptions(key) }
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move to") },
            text = {
                Column {
                    if (options.isEmpty()) Text("There's no other free time for it before it's due.")
                    options.forEach { option ->
                        Text(
                            option.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    moving = null
                                    viewModel.moveTo(key, option.interval)
                                }
                                .testTag(WeeklyReviewTags.MOVE_OPTION)
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Line(line: ReviewLineUi) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        line.colorArgb?.let {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(it.toInt())))
            Spacer(Modifier.width(8.dp))
        }
        Text(line.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(line.text, style = MaterialTheme.typography.bodyMedium)
    }
}
