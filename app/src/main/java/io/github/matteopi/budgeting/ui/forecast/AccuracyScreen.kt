package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import io.github.matteopi.budgeting.core.analysis.CategoryAccuracy
import io.github.matteopi.budgeting.core.analysis.HorizonAccuracy
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.SectionCard
import kotlin.math.roundToInt

/**
 * How well past forecasts matched reality: error at 7/14/28 days for the full model vs. a simple
 * tier-1 baseline, how often the real balance stayed inside the shaded P10-P90 band, per-category
 * errors, and one verdict line (docs/PLAN.md §6.5 "Accuracy").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccuracyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccuracyViewModel = viewModel(factory = AccuracyViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Accuracy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val report = state.report
        val hasData = report != null && report.horizons.any { it.evaluated > 0 }

        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            !hasData -> EmptyState(
                message = "Accuracy appears after a week of data.",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )

            else -> {
                val safeReport = requireNotNull(report)
                val categoryNames = state.categories.associate { it.id to it.name }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    safeReport.improvementPercent?.let { improvement ->
                        item { VerdictCard(improvement) }
                    }

                    items(safeReport.horizons.filter { it.evaluated > 0 }) { horizon ->
                        HorizonCard(horizon)
                    }

                    if (safeReport.categories.isNotEmpty()) {
                        item { Text("Per-category week-ahead error", style = MaterialTheme.typography.titleMedium) }
                        items(safeReport.categories) { category ->
                            CategoryAccuracyRow(category, categoryNames[category.categoryId] ?: "Unknown category")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(improvementPercent: Double) {
    val text = if (improvementPercent > 0) {
        "The full model is ${improvementPercent.roundToInt()}% more accurate than a simple guess."
    } else {
        "The full model hasn't beaten a simple guess yet — it should improve as more data comes in."
    }
    SectionCard {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HorizonCard(horizon: HorizonAccuracy) {
    SectionCard(title = "At ${horizon.days} days") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Full model, average error", style = MaterialTheme.typography.bodyMedium)
            MoneyText(horizon.meanAbsErrorFull, isBalance = true)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Simple guess, average error", style = MaterialTheme.typography.bodyMedium)
            MoneyText(horizon.meanAbsErrorTier1, isBalance = true)
        }
        horizon.coverage?.let { coverage ->
            Text(
                "The real balance was inside the shaded range ${(coverage * 100).roundToInt()}% of the time; " +
                    "about 80% is ideal.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CategoryAccuracyRow(category: CategoryAccuracy, name: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        MoneyText(category.meanAbsErrorFull, isBalance = true)
    }
}
