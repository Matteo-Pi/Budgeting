package io.github.matteopi.budgeting.ui.insights

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.analysis.CategoryRegimeAverage
import io.github.matteopi.budgeting.core.analysis.MonthInOut
import io.github.matteopi.budgeting.core.analysis.WeekTotals
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.components.charts.BarChart
import io.github.matteopi.budgeting.ui.components.charts.BarChartEntry
import io.github.matteopi.budgeting.ui.components.charts.BarChartGroup
import io.github.matteopi.budgeting.ui.components.charts.LineChart
import io.github.matteopi.budgeting.ui.components.charts.LineChartSeries
import io.github.matteopi.budgeting.ui.components.charts.StackedBarGroup
import io.github.matteopi.budgeting.ui.components.charts.StackedBarSegment
import io.github.matteopi.budgeting.ui.components.charts.WeeklyStackedBarChart
import io.github.matteopi.budgeting.ui.components.charts.rememberChartColors
import io.github.matteopi.budgeting.ui.forecast.toShortLabel
import java.time.LocalDate

/**
 * The Insights tab: weekly spending by category, this month vs. average, term vs. holiday
 * averages, balance history and income vs. spending, over a chosen period. Tapping a category
 * expands its own weekly bars with a median line (docs/PLAN.md §6.5 "Insights").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    modifier: Modifier = Modifier,
    viewModel: InsightsViewModel = viewModel(factory = InsightsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categoryNames = state.categories.associate { it.id to it.name }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Insights") }) },
    ) { innerPadding ->
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
                    InsightsPeriod.entries.forEach { option ->
                        FilterChip(
                            selected = state.period == option,
                            onClick = { viewModel.selectPeriod(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Weekly spending") {
                    WeeklySpendingChart(state.weeklyByCategory, state.categories)
                }
            }

            item {
                SectionCard(title = "This month vs. average") {
                    MonthVsAverageList(
                        comparisons = state.thisMonthVsAverage,
                        categoryNames = categoryNames,
                        selectedCategoryId = state.selectedCategoryId,
                        onSelectCategory = { viewModel.selectCategory(it) },
                    )
                }
            }

            state.selectedCategoryId?.let { categoryId ->
                val category = state.categories.firstOrNull { it.id == categoryId }
                if (category != null) {
                    item {
                        SectionCard(title = category.name) {
                            CategoryWeeklyDetail(category, state.weeklyByCategory)
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Term vs. holiday") {
                    TermVsHolidayList(state.termVsHoliday, categoryNames)
                }
            }

            item {
                SectionCard(title = "Balance history") {
                    BalanceHistoryChart(state.balanceHistory)
                }
            }

            item {
                SectionCard(title = "Income vs. spending") {
                    IncomeVsSpendingChart(state.monthlyInOut)
                }
            }
        }
    }
}

@Composable
private fun WeeklySpendingChart(weekly: List<WeekTotals>, categories: List<Category>) {
    val outCategories = categories.filter { it.flow == CategoryFlow.OUT }
    val hasAnySpending = weekly.any { week -> outCategories.any { (week.totals[it.id] ?: Money.ZERO).isNegative } }
    if (!hasAnySpending) {
        EmptyState(message = "No spending recorded yet.")
        return
    }
    val groups = weekly.map { week ->
        val segments = outCategories.mapNotNull { category ->
            val amount = week.totals[category.id] ?: Money.ZERO
            if (!amount.isNegative) return@mapNotNull null
            StackedBarSegment(
                label = category.name,
                color = categoryColor(category),
                amount = amount.abs(),
            )
        }
        StackedBarGroup(label = week.weekStart.toShortLabel(), segments = segments)
    }
    WeeklyStackedBarChart(groups = groups)
}

@Composable
private fun MonthVsAverageList(
    comparisons: List<CategoryMonthComparison>,
    categoryNames: Map<Long, String>,
    selectedCategoryId: Long?,
    onSelectCategory: (Long) -> Unit,
) {
    val nonZero = comparisons.filter { it.thisMonth.pence != 0L || it.average.pence != 0L }
    if (nonZero.isEmpty()) {
        EmptyState(message = "Nothing logged this month yet.")
        return
    }
    Column {
        nonZero.forEach { comparison ->
            Card(
                onClick = { onSelectCategory(comparison.categoryId) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        categoryNames[comparison.categoryId] ?: "Category",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row {
                        MoneyText(comparison.thisMonth)
                        Text(" / avg ", style = MaterialTheme.typography.bodySmall)
                        MoneyText(comparison.average, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryWeeklyDetail(category: Category, weekly: List<WeekTotals>) {
    val color = categoryColor(category)
    val entries = weekly.mapNotNull { week ->
        val amount = week.totals[category.id] ?: return@mapNotNull null
        BarChartEntry(label = week.weekStart.toShortLabel(), value = amount, color = color)
    }
    if (entries.isEmpty()) {
        EmptyState(message = "No data for this category yet.")
        return
    }
    val median = InsightsMapping.median(entries.map { it.value.pence })
    BarChart(
        groups = entries.map { BarChartGroup(it.label, listOf(it)) },
        referenceLine = Money(median),
        referenceLabel = "Median",
    )
}

@Composable
private fun TermVsHolidayList(averages: List<CategoryRegimeAverage>, categoryNames: Map<Long, String>) {
    if (averages.isEmpty()) {
        EmptyState(message = "Not enough history yet to compare term and holiday spending.")
        return
    }
    val byCategory = averages.groupBy { it.categoryId }
    Column {
        byCategory.forEach { (categoryId, regimeAverages) ->
            val term = regimeAverages.firstOrNull { it.regime == Regime.TERM }
            val holiday = regimeAverages.firstOrNull { it.regime == Regime.HOLIDAY }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(categoryNames[categoryId] ?: "Category", style = MaterialTheme.typography.bodyMedium)
                Row {
                    Text("Term ", style = MaterialTheme.typography.bodySmall)
                    MoneyText(term?.averageWeekly ?: Money.ZERO, style = MaterialTheme.typography.bodySmall)
                    Text("  Holiday ", style = MaterialTheme.typography.bodySmall)
                    MoneyText(holiday?.averageWeekly ?: Money.ZERO, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BalanceHistoryChart(balanceHistory: List<Pair<LocalDate, Money>>) {
    if (balanceHistory.isEmpty()) {
        EmptyState(message = "No balance history yet.")
        return
    }
    val colors = rememberChartColors()
    LineChart(series = listOf(LineChartSeries("Balance", colors.seriesPrimary, balanceHistory)))
}

@Composable
private fun IncomeVsSpendingChart(monthlyInOut: List<MonthInOut>) {
    val hasData = monthlyInOut.any { it.totalIn.pence != 0L || it.totalOut.pence != 0L }
    if (!hasData) {
        EmptyState(message = "No income or spending recorded yet.")
        return
    }
    val colors = rememberChartColors()
    val groups = monthlyInOut.map { m ->
        BarChartGroup(
            label = m.month.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
            bars = listOf(
                BarChartEntry("In", m.totalIn, colors.good),
                BarChartEntry("Out", m.totalOut, colors.critical),
            ),
        )
    }
    BarChart(groups = groups)
}

@Composable
private fun categoryColor(category: Category): Color {
    val colors = rememberChartColors()
    return category.colorArgb?.let { Color(it.toInt()) } ?: colors.seriesPrimary
}
