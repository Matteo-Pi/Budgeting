package io.github.matteopi.budgeting.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.components.EmptyState
import java.time.LocalDate

/** Term dates list + add/edit, with overlap warnings (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermDatesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TermDatesViewModel = viewModel(factory = TermDatesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dialogState by remember { mutableStateOf<TermDialogState?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Term dates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                dialogState = TermDialogState(id = 0, name = "", start = state.today, end = state.today)
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add term")
            }
        },
    ) { innerPadding ->
        if (state.terms.isEmpty() && !state.loading) {
            EmptyState(
                message = "No term dates yet — tap + to add your first term.",
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = state.currentTermName?.let { "Today: term ($it)" } ?: "Today: holiday",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(state.terms) { term ->
                    val isCurrent = term.id == state.currentTermId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                dialogState = TermDialogState(term.id, term.name, term.startDate, term.endDate)
                            },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = term.name + if (isCurrent) " (current)" else "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "${formatShortDate(term.startDate)} - ${formatShortDate(term.endDate)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    val dialog = dialogState
    if (dialog != null) {
        TermEditDialog(
            initial = dialog,
            existingTerms = state.terms,
            onDismiss = { dialogState = null },
            onSave = { term ->
                viewModel.save(term)
                dialogState = null
            },
            onDelete = if (dialog.id != 0L) {
                {
                    viewModel.delete(dialog.id)
                    dialogState = null
                }
            } else {
                null
            },
        )
    }
}

private data class TermDialogState(val id: Long, val name: String, val start: LocalDate, val end: LocalDate)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermEditDialog(
    initial: TermDialogState,
    existingTerms: List<TermPeriod>,
    onDismiss: () -> Unit,
    onSave: (TermPeriod) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }

    val endBeforeStart = TermValidation.endBeforeStart(start, end)
    val overlaps = if (!endBeforeStart) {
        TermValidation.findOverlaps(start, end, excludingId = initial.id.takeIf { it != 0L }, existing = existingTerms)
    } else {
        emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Add term" else "Edit term") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Autumn)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                DateField(label = "Start date", date = start, onDateChange = { start = it })
                DateField(label = "End date", date = end, onDateChange = { end = it })
                if (endBeforeStart) {
                    Text(
                        text = "The end date can't be before the start date.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (overlaps.isNotEmpty()) {
                    Text(
                        text = "Overlaps with " + overlaps.joinToString(", ") { it.name } + ".",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete this term") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(TermPeriod(id = initial.id, name = name.trim(), startDate = start, endDate = end)) },
                enabled = name.isNotBlank() && !endBeforeStart,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
