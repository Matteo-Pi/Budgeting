package io.github.matteopi.budgeting.ui.insights

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.receipts.PricePoint
import io.github.matteopi.budgeting.ui.activity.toShortLabelWithYear
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptMapping
import io.github.matteopi.budgeting.ui.theme.LocalMoneyColors

/**
 * One item's unit-price history (date, store, price, newest first) and what it cost over the
 * selected period (docs/PLAN.md §10.4 "Insights → Items").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    productId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemDetailViewModel = viewModel(factory = remember(productId) { ItemDetailViewModel.factory(productId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.found) state.name else "Item") },
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
                    message = "This item is not on any receipt.",
                    modifier = Modifier.padding(innerPadding).fillMaxSize(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InsightsPeriod.entries.forEach { option ->
                            FilterChip(
                                selected = state.period == option,
                                onClick = { viewModel.selectPeriod(option) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
                item { SpendCard(state) }
                item { HistoryCard(state) }
            }
        }
    }
}

@Composable
private fun SpendCard(state: ItemDetailUiState) {
    SectionCard(title = "Spent over ${state.period.label}") {
        Text(
            text = state.spend.format(),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        val purchaseWord = if (state.purchases == 1) "purchase" else "purchases"
        Text(
            text = if (state.purchases == 0) {
                "Not bought in this period."
            } else {
                "${state.quantity} bought in ${state.purchases} $purchaseWord"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryCard(state: ItemDetailUiState) {
    SectionCard(title = "Price history") {
        val change = state.change
        if (change != null && !change.difference.isZero) {
            val rose = change.difference.isPositive
            Text(
                text = "${ItemInsights.changeText(change.percent, rose)} since ${change.previous.unitPrice.format()}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (rose) MaterialTheme.colorScheme.error else LocalMoneyColors.current.income,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Date", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("Store", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("Unit price", style = MaterialTheme.typography.labelMedium)
        }
        state.history.reversed().forEach { point -> HistoryRow(point) }
    }
}

@Composable
private fun HistoryRow(point: PricePoint) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(point.date.toShortLabelWithYear(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            ReceiptMapping.storeName(point.store),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(point.unitPrice.format(), style = MaterialTheme.typography.bodyMedium)
    }
}
