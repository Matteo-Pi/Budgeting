package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.Regime

/** Step 7: a term and holiday weekly-spending guess per learned category, saved as PRIOR predictions (docs/PLAN.md §6.5). */
@Composable
fun SpendingEstimatesStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    val categories = state.categories.filter {
        it.flow == CategoryFlow.OUT && it.forecastMode == ForecastMode.LEARNED && !it.archived && !it.isSystem
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Weekly spending estimates", style = MaterialTheme.typography.headlineSmall)
        Text(
            "These are just starting guesses — the app learns from what you log, so don't worry " +
                "about getting them exactly right.",
            style = MaterialTheme.typography.bodyMedium,
        )
        categories.forEach { category ->
            CategoryEstimateRow(
                category = category,
                termPrediction = state.predictions.firstOrNull { it.categoryId == category.id && it.regime == Regime.TERM },
                holidayPrediction = state.predictions.firstOrNull { it.categoryId == category.id && it.regime == Regime.HOLIDAY },
                onTermChange = { viewModel.setWeeklyEstimate(category.id, Regime.TERM, it) },
                onHolidayChange = { viewModel.setWeeklyEstimate(category.id, Regime.HOLIDAY, it) },
                onNoSpend = { viewModel.setNoSpend(category.id) },
            )
        }
    }
}

@Composable
private fun CategoryEstimateRow(
    category: Category,
    termPrediction: Prediction?,
    holidayPrediction: Prediction?,
    onTermChange: (Money) -> Unit,
    onHolidayChange: (Money) -> Unit,
    onNoSpend: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(category.name, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EstimateField(
                    label = "In term",
                    prediction = termPrediction,
                    modifier = Modifier.weight(1f),
                    onChange = onTermChange,
                )
                EstimateField(
                    label = "In the holidays",
                    prediction = holidayPrediction,
                    modifier = Modifier.weight(1f),
                    onChange = onHolidayChange,
                )
            }
            TextButton(onClick = onNoSpend) { Text("I don't spend on this") }
        }
    }
}

@Composable
private fun EstimateField(
    label: String,
    prediction: Prediction?,
    onChange: (Money) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(prediction?.weeklyAmount) {
        mutableStateOf(prediction?.weeklyAmount?.format(showPence = false)?.removePrefix("£") ?: "")
    }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            AmountText.parsePositiveOrNull(newText)?.let(onChange)
        },
        label = { Text(label) },
        isError = !AmountText.isValidOrBlank(text),
        modifier = modifier,
    )
}
