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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText

/** Sinking funds list: reserved so far / target, weekly contribution (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SinkingFundsScreen(
    onBack: () -> Unit,
    onOpenFund: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SinkingFundsViewModel = viewModel(factory = SinkingFundsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sinking funds") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenFund(null) }) {
                Icon(Icons.Default.Add, contentDescription = "Add sinking fund")
            }
        },
    ) { innerPadding ->
        if (state.active.isEmpty() && state.other.isEmpty() && !state.loading) {
            EmptyState(
                message = "No sinking funds yet — tap + to set money aside for something coming up.",
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fundSection(null, state.active, onOpenFund)
                fundSection("Done and cancelled", state.other, onOpenFund)
            }
        }
    }
}

private fun LazyListScope.fundSection(title: String?, rows: List<SinkingFundRow>, onOpenFund: (Long?) -> Unit) {
    if (rows.isEmpty()) return
    if (title != null) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
    }
    items(rows) { row -> SinkingFundCard(row, onOpenFund) }
}

@Composable
private fun SinkingFundCard(row: SinkingFundRow, onOpenFund: (Long?) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenFund(row.fund.id) },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.fund.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (row.overdue) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Overdue",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = "Target ${row.fund.target.format()} by ${formatShortDate(row.fund.targetDate)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.fund.status == SinkingFundStatus.ACTIVE) {
                LinearProgressIndicator(
                    progress = { row.progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Reserved today", style = MaterialTheme.typography.bodySmall)
                        MoneyText(amount = row.reservedToday)
                    }
                    Column {
                        Text("Per week", style = MaterialTheme.typography.bodySmall)
                        MoneyText(amount = row.weeklyContribution)
                    }
                }
            } else {
                Text(
                    text = if (row.fund.status == SinkingFundStatus.COMPLETED) "Done" else "Cancelled",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
