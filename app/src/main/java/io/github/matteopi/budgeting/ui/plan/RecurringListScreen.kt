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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText

/** Recurring rules (subscriptions, rent, wages, ...) list (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringListScreen(
    onBack: () -> Unit,
    onOpenRecurring: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecurringListViewModel = viewModel(factory = RecurringListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isEmpty = state.moneyIn.isEmpty() && state.regularCosts.isEmpty() &&
        state.transfers.isEmpty() && state.inactive.isEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Regular money") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenRecurring(null) }) {
                Icon(Icons.Default.Add, contentDescription = "Add recurring rule")
            }
        },
    ) { innerPadding ->
        if (isEmpty && !state.loading) {
            EmptyState(
                message = "No recurring money yet — tap + to add rent, wages or a subscription.",
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recurringSection("Money in", state.moneyIn, onOpenRecurring)
                recurringSection("Regular costs", state.regularCosts, onOpenRecurring)
                recurringSection("Transfers", state.transfers, onOpenRecurring)
                recurringSection("Inactive", state.inactive, onOpenRecurring)
            }
        }
    }
}

private fun LazyListScope.recurringSection(
    title: String,
    rows: List<RecurringRow>,
    onOpenRecurring: (Long?) -> Unit,
) {
    if (rows.isEmpty()) return
    item {
        Text(
            text = "$title (${rows.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
    items(rows) { row -> RecurringRowCard(row, onOpenRecurring) }
}

@Composable
private fun RecurringRowCard(row: RecurringRow, onOpenRecurring: (Long?) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenRecurring(row.rule.id) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.rule.label.ifBlank { row.categoryName ?: "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (row.rule.active) TextDecoration.None else TextDecoration.LineThrough,
                )
                val detail = if (row.direction == Direction.TRANSFER) {
                    "${row.accountName} -> ${row.toAccountName ?: "?"} - ${row.description}"
                } else {
                    "${row.categoryName ?: ""} - ${row.accountName} - ${row.description}"
                }
                Text(text = detail, style = MaterialTheme.typography.bodySmall)
                if (row.nextDate != null) {
                    Text(
                        text = "Next: ${formatShortDate(row.nextDate)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            MoneyText(amount = row.rule.amount, isBalance = false)
        }
    }
}
