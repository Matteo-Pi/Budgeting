package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.forecast.BudgetPeriod
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.AccountViewSelector
import io.github.matteopi.budgeting.ui.components.ForecastErrorCard
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.components.charts.ForecastChart
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton

/**
 * The Forecast tab: horizon chips, the P10-P90 chart, the risk sentence, lumpy-income budget
 * periods, and links to Predictions/What-if/Timeline/Accuracy (docs/PLAN.md §6.5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForecastScreen(
    onOpenPredictions: () -> Unit,
    onOpenScenarios: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenAccuracy: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForecastViewModel = viewModel(factory = ForecastViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Forecast") },
                navigationIcon = { SectionSwitchButton(AppSection.BUDGETING) },
            )
        },
    ) { innerPadding ->
        val result = state.result
        val chart = state.chart
        if (result == null || chart == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                val error = state.error
                if (error != null) {
                    Text(
                        text = "Couldn't update the forecast: $error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }

        val horizonEnd = ForecastChartMapping.horizonEndDate(state.horizon, result)
        val shown = AccountChartMapping.cutAt(chart, horizonEnd)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ForecastHorizonOption.entries.forEach { option ->
                        FilterChip(
                            selected = state.horizon == option,
                            onClick = { viewModel.selectHorizon(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

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

            item {
                SectionCard {
                    ForecastChart(
                        days = shown.days,
                        buffer = shown.buffer,
                        today = result.today,
                        showTier1 = state.showTier1 && shown.hasTier1,
                        lines = shown.lines,
                    )
                    if (shown.hasTier1) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Show tier-1 baseline", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = state.showTier1, onCheckedChange = { viewModel.toggleTier1() })
                        }
                    }
                }
            }

            item {
                val poolSentence = ForecastChartMapping.riskSentence(result.risk, result.safeToSpend.horizonEnd)
                // The risk figure is always about the accounts included in safe-to-spend, so say so
                // whenever the chart shows something else (hasTier1 is true only for the pool's own
                // data, which "All accounts" also is when no account is left out of safe-to-spend).
                val sentence = if (state.view == AccountView.Pool || shown.hasTier1) {
                    poolSentence
                } else {
                    "Across the accounts in safe-to-spend: $poolSentence"
                }
                val isHighRisk = (result.risk?.probBelowFloor ?: 0.0) >= 0.10
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (isHighRisk) Icons.Default.Warning else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isHighRisk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text(sentence, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Text("Budget periods", style = MaterialTheme.typography.titleMedium)
            }

            items(result.budgetPeriods) { period ->
                BudgetPeriodCard(period)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkRow(
                        icon = Icons.Default.Edit,
                        label = "Spending estimates",
                        badgeCount = result.needsPrediction.size,
                        onClick = onOpenPredictions,
                    )
                    LinkRow(icon = Icons.Default.Refresh, label = "What-if scenarios", onClick = onOpenScenarios)
                    LinkRow(icon = Icons.Default.DateRange, label = "Timeline", onClick = onOpenTimeline)
                    LinkRow(icon = Icons.Default.Info, label = "Accuracy", onClick = onOpenAccuracy)
                }
            }
        }
    }
}

@Composable
private fun BudgetPeriodCard(period: BudgetPeriod) {
    val isDeficit = period.availableForSpending.isNegative
    SectionCard(title = "${period.start.toShortLabel()} – ${period.end.toShortLabel()}") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Starting income", style = MaterialTheme.typography.bodyMedium)
            MoneyText(period.startingIncome)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Committed costs", style = MaterialTheme.typography.bodyMedium)
            MoneyText(period.committedOut)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Available for spending", style = MaterialTheme.typography.bodyMedium)
            MoneyText(period.availableForSpending, isBalance = true)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Per week", style = MaterialTheme.typography.bodyMedium)
            MoneyText(period.perWeek, isBalance = true)
        }
        if (isDeficit) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                Text(
                    "This period is a deficit — costs outrun income even with no other spending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (badgeCount > 0) {
                    BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                        Icon(icon, contentDescription = null)
                    }
                } else {
                    Icon(icon, contentDescription = null)
                }
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
