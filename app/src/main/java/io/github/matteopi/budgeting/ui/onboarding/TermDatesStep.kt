package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.plan.TermValidation
import java.time.LocalDate

/** Step 4: add/edit/delete terms, with the overlap and end-before-start checks (docs/PLAN.md §6.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermDatesStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    var dialogState by remember { mutableStateOf<TermOnboardingDialogState?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your term dates", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Find these on your university's website. The forecast uses them to tell term-time " +
                "spending from holiday spending.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.terms.isEmpty()) {
            EmptyState(message = "No term dates yet.")
        } else {
            state.terms.sortedBy { it.startDate }.forEach { term ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            dialogState = TermOnboardingDialogState(term.id, term.name, term.startDate, term.endDate)
                        },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(term.name, style = MaterialTheme.typography.titleMedium)
                        Text("${term.startDate} - ${term.endDate}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        OutlinedButton(onClick = {
            dialogState = TermOnboardingDialogState(id = 0, name = "", start = state.today, end = state.today)
        }) {
            Text("Add term")
        }
    }

    val dialog = dialogState
    if (dialog != null) {
        TermOnboardingDialog(
            initial = dialog,
            existingTerms = state.terms,
            onDismiss = { dialogState = null },
            onSave = { term -> viewModel.saveTerm(term); dialogState = null },
            onDelete = if (dialog.id != 0L) {
                { viewModel.deleteTerm(dialog.id); dialogState = null }
            } else {
                null
            },
        )
    }
}

private data class TermOnboardingDialogState(val id: Long, val name: String, val start: LocalDate, val end: LocalDate)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermOnboardingDialog(
    initial: TermOnboardingDialogState,
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
                        "The end date can't be before the start date.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (overlaps.isNotEmpty()) {
                    Text(
                        "Overlaps with " + overlaps.joinToString(", ") { it.name } + ".",
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
