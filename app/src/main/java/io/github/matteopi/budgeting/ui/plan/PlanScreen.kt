package io.github.matteopi.budgeting.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton

/** The Plan tab: hub linking to money in/out, one-off dates, term dates, funds, estimates (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onOpenRecurring: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenTermDates: () -> Unit,
    onOpenStudentLoanHelper: () -> Unit,
    onOpenSinkingFunds: () -> Unit,
    onOpenPredictions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlanViewModel = viewModel(factory = PlanViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Plan") },
                navigationIcon = { SectionSwitchButton(AppSection.BUDGETING) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = state.todayRegimeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            val rows = listOf(
                PlanRow("Money in", "Recurring and scheduled income", state.moneyInCount, onOpenRecurring),
                PlanRow("Regular costs", "Rent, subscriptions and other recurring spending", state.regularCostsCount, onOpenRecurring),
                PlanRow("One-off dates", "Specific future amounts", state.oneOffCount, onOpenScheduled),
                PlanRow("Term dates", "Terms and holidays used to shape estimates", state.termCount, onOpenTermDates),
                PlanRow("Sinking funds", "Money set aside for future costs", state.activeSinkingFundsCount, onOpenSinkingFunds),
                PlanRow("Student loan helper", "Split a year's total into three instalments", null, onOpenStudentLoanHelper),
                PlanRow(
                    "Spending estimates",
                    "Automatic vs your own weekly estimates",
                    state.needsPredictionCount.takeIf { it > 0 },
                    onOpenPredictions,
                ),
            )
            items(rows) { row -> PlanHubRow(row) }
        }
    }
}

private data class PlanRow(
    val title: String,
    val subtitle: String,
    val count: Int?,
    val onClick: () -> Unit,
)

@Composable
private fun PlanHubRow(row: PlanRow) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = row.onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.title, style = MaterialTheme.typography.titleMedium)
                Text(text = row.subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (row.count != null) {
                Text(
                    text = row.count.toString(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
