package io.github.matteopi.budgeting.ui.receipts.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.activity.toShortLabelWithYear
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.theme.LocalMoneyColors

/**
 * One receipt: header, items, the transactions it was split into, and Delete with a choice
 * between merging the split back into one transaction and keeping it (docs/PLAN.md §10.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptDetailScreen(
    receiptId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReceiptDetailViewModel = viewModel(factory = remember(receiptId) { ReceiptDetailViewModel.factory(receiptId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDelete by remember { mutableStateOf(false) }

    // Once deleted there is nothing left to show.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.found) state.storeName else "Receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!state.found) {
            if (!state.loading) {
                EmptyState(
                    message = "This receipt no longer exists.",
                    modifier = Modifier.padding(innerPadding).fillMaxSize(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { HeaderCard(state) }
                item {
                    SectionCard(title = "Items") {
                        if (state.items.isEmpty()) {
                            Text(
                                text = "No items were saved with this receipt.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        state.items.forEach { row -> ItemRow(row) }
                    }
                }
                if (state.splits.isNotEmpty()) {
                    item {
                        SectionCard(title = "Transactions") {
                            state.splits.forEach { split ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(split.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(split.amount.format(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete receipt")
                    }
                }
            }
        }
    }

    if (showDelete) {
        DeleteReceiptDialog(
            onConfirm = { mergeBack ->
                showDelete = false
                viewModel.delete(mergeBack)
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun HeaderCard(state: ReceiptDetailUiState) {
    SectionCard {
        Text(state.storeName, style = MaterialTheme.typography.headlineSmall)
        state.date?.let { date ->
            Text(
                text = date.toShortLabelWithYear(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = state.total.format(),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        state.accountName?.let { name ->
            Text(
                text = "Paid from $name",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ItemRow(row: ReceiptItemRow) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                val detail = listOfNotNull(row.quantityText, row.categoryName).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(row.price.format(), style = MaterialTheme.typography.bodyLarge)
                if (!row.discount.isZero) {
                    Text(
                        text = row.discount.format(),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMoneyColors.current.income,
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/** Asks what to do with the split transactions; "Merge back into one transaction" is the default. */
@Composable
private fun DeleteReceiptDialog(onConfirm: (mergeBack: Boolean) -> Unit, onDismiss: () -> Unit) {
    var mergeBack by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete receipt?") },
        text = {
            Column {
                Text(
                    text = "The items on this receipt are removed. What should happen to the transactions it was split into?",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                )
                ChoiceRow("Merge back into one transaction", selected = mergeBack, onSelect = { mergeBack = true })
                ChoiceRow("Keep the split transactions", selected = !mergeBack, onSelect = { mergeBack = false })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mergeBack) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
