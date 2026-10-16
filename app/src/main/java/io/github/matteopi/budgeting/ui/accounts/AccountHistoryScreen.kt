package io.github.matteopi.budgeting.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.ui.activity.ActivityRow
import io.github.matteopi.budgeting.ui.activity.DirectionFilter
import io.github.matteopi.budgeting.ui.activity.TransactionEditHost
import io.github.matteopi.budgeting.ui.activity.toShortLabelWithYear
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.PickerField
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.components.charts.LineChart
import io.github.matteopi.budgeting.ui.components.charts.LineChartSeries
import io.github.matteopi.budgeting.ui.components.charts.rememberChartColors

/** Test tags on the history screen (shared with the device tests). */
object AccountHistoryTags {
    const val CHART = "history-chart"
    const val SUMMARY_START = "history-summary-start"
    const val SUMMARY_END = "history-summary-end"
    const val SUMMARY_IN = "history-summary-in"
    const val SUMMARY_OUT = "history-summary-out"

    fun range(range: HistoryRange): String = "history-range-${range.name}"

    fun direction(direction: DirectionFilter): String = "history-direction-${direction.name}"
}

/**
 * One account's full history (docs/PLAN.md §6.5): a balance-over-time chart with range chips, a summary of the
 * range, filters, and every transaction grouped by month and day with the running balance after each one. Tapping
 * a row opens the same edit sheet as Activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountHistoryScreen(
    accountId: Long,
    onBack: () -> Unit,
    onOpenReceipt: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AccountHistoryViewModel = viewModel(factory = remember(accountId) { AccountHistoryViewModel.factory(accountId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    var editingRow by remember { mutableStateOf<ActivityRow?>(null) }
    val categoriesById = remember(state.categories) { state.categories.associateBy { it.id } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.account?.let { "${it.name} history" } ?: "History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "range") {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HistoryRange.entries.forEach { option ->
                        FilterChip(
                            selected = state.range == option,
                            onClick = { viewModel.setRange(option) },
                            label = { Text(option.label) },
                            modifier = Modifier.testTag(AccountHistoryTags.range(option)),
                        )
                    }
                }
            }

            item(key = "chart") {
                SectionCard(
                    title = "Balance over time",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    if (state.chartPoints.isEmpty()) {
                        EmptyState(message = "No history yet for this account.")
                    } else {
                        val colors = rememberChartColors()
                        LineChart(
                            series = listOf(
                                LineChartSeries(state.account?.name ?: "Balance", colors.seriesPrimary, state.chartPoints),
                            ),
                            modifier = Modifier.padding(top = 8.dp).testTag(AccountHistoryTags.CHART),
                        )
                    }
                }
            }

            state.summary?.let { summary ->
                item(key = "summary") {
                    SectionCard(
                        title = "${summary.from.toShortLabelWithYear()} to ${summary.to.toShortLabelWithYear()}",
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SummaryFigure("Balance at start") {
                                MoneyText(
                                    amount = summary.startBalance,
                                    modifier = Modifier.testTag(AccountHistoryTags.SUMMARY_START),
                                    isBalance = true,
                                )
                            }
                            SummaryFigure("Balance at end", horizontalAlignment = Alignment.End) {
                                MoneyText(
                                    amount = summary.endBalance,
                                    modifier = Modifier.testTag(AccountHistoryTags.SUMMARY_END),
                                    isBalance = true,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SummaryFigure("Money in") {
                                MoneyText(summary.moneyIn, modifier = Modifier.testTag(AccountHistoryTags.SUMMARY_IN))
                            }
                            SummaryFigure("Money out", horizontalAlignment = Alignment.End) {
                                Text(
                                    text = summary.moneyOut.format(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.testTag(AccountHistoryTags.SUMMARY_OUT),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "filters") {
                HistoryFiltersBar(
                    filters = filters,
                    categories = state.usedCategories,
                    shown = state.history.shownRows,
                    total = state.history.totalRows,
                    onSearch = viewModel::setSearch,
                    onCategory = viewModel::setCategory,
                    onDirection = viewModel::setDirection,
                    onClear = viewModel::clearFilters,
                )
            }

            if (!state.loading && state.history.months.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        message = if (state.history.totalRows == 0) {
                            "No transactions yet for this account."
                        } else {
                            "Nothing matches these filters."
                        },
                    )
                }
            }

            state.history.months.forEach { month ->
                // Not sticky: a pinned header would cover the row a list scrolls to the top (and a test taps).
                item(key = "month-${month.month}") { MonthHeader(month) }
                month.days.forEach { day ->
                    item(key = "day-${day.date}") {
                        Text(
                            text = day.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(day.rows, key = { it.tx.id }) { row ->
                        HistoryRowView(
                            row = row,
                            dotColor = row.tx.categoryId?.let { categoriesById[it] }.dotColor(),
                            onClick = { editingRow = row.editRow },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }

    TransactionEditHost(
        row = editingRow,
        onDismiss = { editingRow = null },
        controller = viewModel.editing,
        accounts = state.accounts,
        categories = state.categories,
        today = state.today,
        perspectiveAccountId = accountId,
        onOpenReceipt = onOpenReceipt,
    )
}

@Composable
private fun SummaryFigure(
    label: String,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    value: @Composable () -> Unit,
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value()
    }
}

/** Search, a category drop-down and In / Out / All chips; they hide rows only (balances and month totals never change). */
@Composable
private fun HistoryFiltersBar(
    filters: HistoryFilters,
    categories: List<Category>,
    shown: Int,
    total: Int,
    onSearch: (String) -> Unit,
    onCategory: (Long?) -> Unit,
    onDirection: (DirectionFilter) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = filters.search,
            onValueChange = onSearch,
            label = { Text("Search") },
            supportingText = { Text("Label, category or note") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))

        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            PickerField(
                value = categories.firstOrNull { it.id == filters.categoryId }?.name ?: "All categories",
                // Not just "Category": the edit sheet that opens over this screen has a field of that name.
                label = "Filter by category",
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("All categories") }, onClick = { onCategory(null); expanded = false })
                categories.forEach { category ->
                    DropdownMenuItem(text = { Text(category.name) }, onClick = { onCategory(category.id); expanded = false })
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(DirectionFilter.IN, DirectionFilter.OUT, DirectionFilter.ALL).forEach { option ->
                FilterChip(
                    selected = filters.direction == option,
                    onClick = { onDirection(option) },
                    label = {
                        Text(
                            when (option) {
                                DirectionFilter.IN -> "In"
                                DirectionFilter.OUT -> "Out"
                                DirectionFilter.ALL -> "All"
                            },
                        )
                    },
                    modifier = Modifier.testTag(AccountHistoryTags.direction(option)),
                )
            }
        }

        if (!filters.isDefault) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Showing $shown of $total transactions. Balances and month totals still count them all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Clear filters") }
            }
        }
    }
}

/** "October 2026 · in £500.00 · out £485.21 · ends at £14.79": the month's totals, above its rows. */
@Composable
private fun MonthHeader(month: HistoryMonth) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider()
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(month.label) }
                    append(month.headerText.removePrefix(month.label))
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** One transaction with the account's balance after it, and badges for what kind of entry it is. */
@Composable
private fun HistoryRowView(
    row: HistoryRow,
    dotColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = dotColor, modifier = Modifier.size(10.dp)) {}
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
            row.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.badges.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    row.badges.forEach { badge -> HistoryBadge(badge) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(amount = row.tx.amount, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = row.balanceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The category's colour, or grey for rows without a category (the same dot as Activity). */
private fun Category?.dotColor(): Color =
    this?.colorArgb?.let { Color(it or 0xFF000000) } ?: Color(0xFF9E9E9E)
