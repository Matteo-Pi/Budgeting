package io.github.matteopi.budgeting.ui.forecast

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.plan.ChoiceRow

/** Per-category, per-regime spending estimates: automatic vs the user's own (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PredictionsViewModel = viewModel(factory = PredictionsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var needsInputOnly by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<PredictionRow?>(null) }

    val visibleRows = state.rows.filter { !needsInputOnly || it.needsInput }
    val groups = visibleRows.groupBy { it.category }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Spending estimates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.rows.isEmpty() && !state.loading) {
            EmptyState(
                message = "No spending categories to estimate yet.",
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
                        text = "These are the weekly amounts the forecast expects you to spend in each " +
                            "category, split into term time and holidays. Automatic estimates come from your " +
                            "spending history; you can replace them with your own number at any time.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Needs input only", modifier = Modifier.weight(1f))
                        Switch(checked = needsInputOnly, onCheckedChange = { needsInputOnly = it })
                    }
                }
                if (visibleRows.isEmpty()) {
                    item {
                        Text(
                            "Nothing needs input right now.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                groups.forEach { (category, rows) ->
                    item {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(rows) { row ->
                        PredictionRowCard(row) {
                            if (!row.readOnly) editingRow = row
                        }
                    }
                }
            }
        }
    }

    val editing = editingRow
    if (editing != null) {
        PredictionEditDialog(
            row = editing,
            viewModel = viewModel,
            onDismiss = { editingRow = null },
        )
    }
}

@Composable
private fun PredictionRowCard(row: PredictionRow, onClick: () -> Unit) {
    val colors = if (row.needsInput) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !row.readOnly, onClick = onClick),
        colors = colors,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (row.regime == Regime.TERM) "Term" else "Holiday",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${row.source.shortLabel()} - ${row.weeksOfData} wk${if (row.weeksOfData == 1) "" else "s"} of data",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (row.needsInput) {
                    Text(
                        text = "Needs input",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            MoneyText(amount = row.finalWeekly)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PredictionEditDialog(
    row: PredictionRow,
    viewModel: PredictionsViewModel,
    onDismiss: () -> Unit,
) {
    var mode by remember(row) { mutableStateOf(row.editMode) }
    var amountText by remember(row) {
        mutableStateOf((row.prediction?.weeklyAmount ?: row.suggestedWeekly ?: Money.ZERO).format().removePrefix("£"))
    }
    var percentText by remember(row) { mutableStateOf((row.prediction?.adjustPercent ?: 0).toString()) }
    var error by remember(row) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${row.category.name} - ${if (row.regime == Regime.TERM) "term" else "holiday"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Automatic estimate: ${row.automaticWeekly.abs().format()} / week",
                    style = MaterialTheme.typography.bodySmall,
                )
                ChoiceRow(
                    options = listOf(PredictionEditMode.AUTOMATIC, PredictionEditMode.MY_ESTIMATE),
                    selected = mode,
                    label = { modeLabel(it) },
                    onSelect = { mode = it },
                )
                ChoiceRow(
                    options = listOf(PredictionEditMode.ALWAYS_USE, PredictionEditMode.ADJUST_PERCENT),
                    selected = mode,
                    label = { modeLabel(it) },
                    onSelect = { mode = it },
                )
                when (mode) {
                    PredictionEditMode.MY_ESTIMATE, PredictionEditMode.ALWAYS_USE -> OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it; error = null },
                        label = { Text("Weekly amount (£)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PredictionEditMode.ADJUST_PERCENT -> OutlinedTextField(
                        value = percentText,
                        onValueChange = { percentText = it; error = null },
                        label = { Text("Adjust by % (e.g. -20)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PredictionEditMode.AUTOMATIC -> Text(
                        "Uses the automatic estimate above, and updates as you log more spending.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (error != null) {
                    Text(text = error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    viewModel.setNoSpend(row)
                    onDismiss()
                }) { Text("I don't spend on this") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = viewModel.save(row, mode, amountText, percentText)
                if (result != null) error = result else onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun modeLabel(mode: PredictionEditMode): String = when (mode) {
    PredictionEditMode.AUTOMATIC -> "Automatic"
    PredictionEditMode.MY_ESTIMATE -> "My estimate"
    PredictionEditMode.ALWAYS_USE -> "Always use my number"
    PredictionEditMode.ADJUST_PERCENT -> "Adjust by %"
}
