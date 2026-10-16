package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.ui.components.AccountViewSelector
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.ForecastErrorCard
import io.github.matteopi.budgeting.ui.components.MoneyText

/**
 * The term-aware weekly calendar: one card per week to the forecast end, showing committed and
 * expected flows, the projected end-of-week balance and reserved sinking-fund money. Weeks whose
 * P10 dips below the floor are highlighted (docs/PLAN.md §6.5 "Timeline").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = viewModel(factory = TimelineViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Timeline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.weeks.isEmpty() -> EmptyState(
                message = state.error?.let { "Couldn't update the forecast: $it" } ?: "Nothing to show yet.",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { error ->
                    item { ForecastErrorCard(error) }
                }
                if (state.accounts.size >= 2) {
                    item {
                        AccountViewSelector(
                            accounts = state.accounts,
                            selected = state.view,
                            onSelect = viewModel::selectView,
                        )
                    }
                }
                items(state.weeks) { week -> WeekCard(week) }
            }
        }
    }
}

@Composable
private fun BalanceRow(label: String, p10: Money?, p50: Money, p90: Money?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(p50, isBalance = true)
            if (p10 != null && p90 != null) {
                Text(
                    "${p10.format(showPence = false)} – ${p90.format(showPence = false)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun WeekCard(week: TimelineWeek) {
    val regimeLabel = if (week.regime == Regime.TERM) {
        "Term" + (week.termName?.let { " ($it)" } ?: "")
    } else {
        "Holiday"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (week.belowFloor) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Week of ${week.weekStart.toShortLabel()} · $regimeLabel",
                style = MaterialTheme.typography.titleSmall,
            )

            if (week.flows.isEmpty()) {
                Text(
                    "No committed money in or out this week.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    week.flows.forEach { flow ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${flow.date.toShortLabel()} · ${flow.label}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            MoneyText(flow.amount, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (week.showPoolFigures) {
                // Spending and income (e.g. wages) are shown apart: their sum read as "spending"
                // looked like a small positive amount whenever income outweighed spending.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Expected spending", style = MaterialTheme.typography.bodyMedium)
                    MoneyText(week.expectedSpending)
                }
                if (!week.expectedIncome.isZero) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Expected income", style = MaterialTheme.typography.bodyMedium)
                        MoneyText(week.expectedIncome)
                    }
                }
            }

            if (week.accountBalances.isEmpty()) {
                BalanceRow(
                    label = "Balance (end of week)",
                    p10 = week.endBalanceP10,
                    p50 = week.endBalanceP50,
                    p90 = week.endBalanceP90,
                )
            } else {
                week.accountBalances.forEach { balance ->
                    BalanceRow(
                        label = "${balance.name} (end of week)",
                        p10 = balance.p10,
                        p50 = balance.p50,
                        p90 = balance.p90,
                    )
                }
            }

            if (week.showPoolFigures && week.showReserved && week.reserved.pence > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Reserved for sinking funds", style = MaterialTheme.typography.bodyMedium)
                    MoneyText(week.reserved)
                }
            }

            if (week.belowFloor) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    Text(
                        "This week could dip below £0.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
