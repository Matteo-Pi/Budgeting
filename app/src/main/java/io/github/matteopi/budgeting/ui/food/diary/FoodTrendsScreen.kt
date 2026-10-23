@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.matteopi.budgeting.ui.food.diary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.nutrition.FoodPrefs
import io.github.matteopi.budgeting.core.nutrition.Insights
import io.github.matteopi.budgeting.core.nutrition.IntakeKey
import io.github.matteopi.budgeting.core.nutrition.Micro
import io.github.matteopi.budgeting.core.nutrition.NutritionInsights
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.ui.components.SectionCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class TrendsUiState(val days: Int = 7, val insights: Insights? = null, val prefs: FoodPrefs = FoodPrefs())

@OptIn(ExperimentalCoroutinesApi::class)
class FoodTrendsViewModel(container: AppContainer) : ViewModel() {
    private val span = MutableStateFlow(7)

    val uiState: StateFlow<TrendsUiState> = span.flatMapLatest { days ->
        val to = container.clock.today()
        val from = to.minusDays(days - 1L)
        combine(
            container.diaryRepository.observeBetween(from, to),
            container.diaryRepository.observeFoodDays(from, to),
            container.diaryRepository.observeBatches(),
            container.settingsStore.foodPrefs,
        ) { items, foodDays, batches, prefs ->
            TrendsUiState(days, NutritionInsights.of(items, from, to, prefs.targets, foodDays, batches), prefs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsUiState())

    fun setDays(days: Int) {
        span.value = days
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                FoodTrendsViewModel(app.container)
            }
        }
    }
}

/**
 * Trends (docs/PLAN.md §14.5): 7 or 28 days of kcal against the target (thin bars, one colour, the target as a dashed
 * line, no second axis), the averages per logged day, the vitamins and minerals as % of the reference with the low
 * ones named, veg, water, cost, the top sources and the kitchen's waste.
 */
@Composable
fun FoodTrendsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodTrendsViewModel = viewModel(factory = FoodTrendsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Trends") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.days == 7, onClick = { viewModel.setDays(7) }, label = { Text("7 days") })
                FilterChip(selected = state.days == 28, onClick = { viewModel.setDays(28) }, label = { Text("28 days") })
            }
            val insights = state.insights ?: return@Column
            if (insights.loggedDays == 0) {
                Text("Nothing logged in these days yet.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            SectionCard(title = "Energy per day") {
                KcalBars(insights, state.prefs.targets.kcal)
                Text(
                    "Average ${kcalText(insights.average.kcal)} on ${insights.loggedDays} logged days · target ${kcalText(state.prefs.targets.kcal)} · " +
                        "${insights.daysOnTarget} days within 10%",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SectionCard(title = "Averages per logged day") {
                listOf(IntakeKey.PROTEIN, IntakeKey.CARBS, IntakeKey.FAT, IntakeKey.FIBRE, IntakeKey.SUGARS, IntakeKey.SATURATES, IntakeKey.SALT)
                    .forEach { NutrientBar(it.label, insights.progress.getValue(it)) }
            }
            SectionCard(title = "Vitamins and minerals") {
                Micro.entries.forEach { micro -> NutrientBar(micro.label, insights.progress.getValue(IntakeKey.of(micro))) }
                Text(
                    if (insights.lowMicros.isEmpty()) {
                        "None is under half its daily reference on average."
                    } else {
                        "Under half the daily reference on average: ${insights.lowMicros.joinToString(", ") { it.label }}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp).testTag("trends-low"),
                )
            }
            SectionCard(title = "Every day") {
                Text("5 a day: ${Quantities.plain(insights.vegPerDay, 1)} portions", style = MaterialTheme.typography.bodyMedium)
                Text("Water: ${Quantities.roundHalfUp(insights.waterPerDay)} ml", style = MaterialTheme.typography.bodyMedium)
                Text("Food cost: ${penceText(insights.costPerDay)} a day", style = MaterialTheme.typography.bodyMedium)
                if (insights.wastedPortions > 0) {
                    Text(
                        "Thrown away: ${countText(insights.wastedPortions)} portions (${penceText(insights.wastedPence)})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            SectionCard(title = "Where it came from") {
                Sources("Energy", insights.topKcal.map { "${it.name} ${Math.round(it.fraction * 100)}%" })
                Sources("Protein", insights.topProtein.map { "${it.name} ${Math.round(it.fraction * 100)}%" })
                Sources("Cost", insights.topCost.map { "${it.name} ${penceText(it.amount)}" })
            }
        }
    }
}

@Composable
private fun Sources(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    Text(lines.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}

/** Thin bars of kcal per day with a 2 dp gap, rounded tops on the baseline, and the target as a dashed line. */
@Composable
private fun KcalBars(insights: Insights, target: Double) {
    val bar = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val maxKcal = maxOf(target * 1.25, insights.days.maxOfOrNull { it.kcal } ?: 0.0).coerceAtLeast(1.0)
    val summary = insights.days.joinToString("; ") { "${shortDay(it.date)} ${Quantities.roundHalfUp(it.kcal)} kcal" }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(vertical = 8.dp)
            .semantics { contentDescription = "Energy per day: $summary" }
            .testTag("trends-bars"),
    ) {
        val n = insights.days.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val width = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        insights.days.forEachIndexed { i, day ->
            val h = (day.kcal / maxKcal * size.height).toFloat()
            if (h > 0f) {
                drawRoundRect(
                    color = bar,
                    topLeft = Offset(i * (width + gap), size.height - h),
                    size = Size(width, h),
                    cornerRadius = CornerRadius(minOf(4.dp.toPx(), width / 2)),
                )
            }
        }
        val y = (size.height - target / maxKcal * size.height).toFloat()
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    }
}
