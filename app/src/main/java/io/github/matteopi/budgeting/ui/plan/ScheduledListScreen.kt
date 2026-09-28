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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText

/** One-off scheduled items (specific future dates) list (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledListScreen(
    onBack: () -> Unit,
    onOpenScheduled: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScheduledListViewModel = viewModel(factory = ScheduledListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isEmpty = state.upcoming.isEmpty() && state.done.isEmpty() && state.skipped.isEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("One-off dates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenScheduled(null) }) {
                Icon(Icons.Default.Add, contentDescription = "Add one-off date")
            }
        },
    ) { innerPadding ->
        if (isEmpty && !state.loading) {
            EmptyState(
                message = "No one-off dates yet — tap + to add a future amount.",
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                scheduledSection("Upcoming", state.upcoming, onOpenScheduled)
                scheduledSection("Done", state.done, onOpenScheduled)
                scheduledSection("Skipped", state.skipped, onOpenScheduled)
            }
        }
    }
}

private fun LazyListScope.scheduledSection(
    title: String,
    rows: List<ScheduledRow>,
    onOpenScheduled: (Long?) -> Unit,
) {
    if (rows.isEmpty()) return
    item {
        Text(
            text = "$title (${rows.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
    items(rows) { row -> ScheduledRowCard(row, onOpenScheduled) }
}

@Composable
private fun ScheduledRowCard(row: ScheduledRow, onOpenScheduled: (Long?) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenScheduled(row.item.id) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.item.label.ifBlank { row.categoryName ?: "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                )
                val detail = if (row.direction == Direction.TRANSFER) {
                    "${row.accountName} -> ${row.toAccountName ?: "?"} - ${formatShortDate(row.item.date)}"
                } else {
                    "${row.categoryName ?: ""} - ${row.accountName} - ${formatShortDate(row.item.date)}"
                }
                Text(text = detail, style = MaterialTheme.typography.bodySmall)
            }
            MoneyText(amount = row.item.amount, isBalance = false)
        }
    }
}
