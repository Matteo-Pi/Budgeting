package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.components.charts.ForecastChart
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Edit a what-if scenario's items and compare it live against the baseline forecast
 * (docs/PLAN.md §6.5 "What-if"). Comparisons and the chart overlay run against the *unsaved*
 * draft via [io.github.matteopi.budgeting.domain.ForecastService.withScenario].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioEditScreen(
    scenarioId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScenarioEditViewModel = viewModel(factory = ScenarioEditViewModel.factory(scenarioId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    LaunchedEffect(closed) {
        if (closed) onBack()
    }
    val scope = rememberCoroutineScope()
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showApplyConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (scenarioId == null) "New scenario" else "Edit scenario") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::saveAndClose) {
                        Icon(Icons.Default.Check, contentDescription = "Save scenario")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingIndex = null
                showEditor = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { viewModel.setName(it) },
                    label = { Text("Scenario name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Text("Items", style = MaterialTheme.typography.titleMedium) }

            if (state.items.isEmpty()) {
                item { EmptyState(message = "No items yet — add a one-off cost, a recurring change or a category adjustment.") }
            } else {
                items(state.items.size) { index ->
                    ScenarioItemRow(
                        item = state.items[index],
                        categories = state.categories,
                        onClick = {
                            editingIndex = index
                            showEditor = true
                        },
                        onDelete = { viewModel.removeItem(index) },
                    )
                }
            }

            state.comparison?.let { comparison ->
                item { ComparisonCard(comparison, computing = state.computing) }
            }

            if (state.baselineChart.isNotEmpty()) {
                item {
                    SectionCard(title = "Chart") {
                        ForecastChart(
                            days = state.baselineChart,
                            buffer = Money.ZERO,
                            today = state.baselineChart.first().date,
                            scenario = state.scenarioChart.ifEmpty { null },
                        )
                    }
                }
            }

            if (state.items.isNotEmpty()) {
                item {
                    TextButton(onClick = { showApplyConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Apply to plan")
                    }
                }
            }
        }
    }

    if (showEditor) {
        ScenarioItemEditor(
            initial = editingIndex?.let { state.items.getOrNull(it) },
            categories = state.categories,
            accounts = state.accounts,
            onSave = { item ->
                val index = editingIndex
                if (index != null) viewModel.updateItem(index, item) else viewModel.addItem(item)
                showEditor = false
                editingIndex = null
            },
            onDismiss = {
                showEditor = false
                editingIndex = null
            },
        )
    }

    if (showApplyConfirm) {
        ConfirmDialog(
            title = "Apply to plan?",
            text = "This turns the scenario's items into real scheduled items, recurring rules and " +
                "spending estimates. It doesn't delete the scenario.",
            onConfirm = {
                scope.launch { viewModel.applyToPlan() }
                showApplyConfirm = false
            },
            onDismiss = { showApplyConfirm = false },
            confirmLabel = "Apply",
        )
    }
}

@Composable
private fun ComparisonCard(comparison: ScenarioComparison, computing: Boolean) {
    SectionCard(title = if (computing) "Comparison (updating…)" else "Comparison vs. baseline") {
        ComparisonRow("Safe to spend / week", comparison.baselineWeekly, comparison.scenarioWeekly)
        ComparisonRow("Balance at horizon end", comparison.baselineEndBalance, comparison.scenarioEndBalance, isBalance = true)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Chance below £0", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(comparison.baselineProbBelowFloor * 100).roundToInt()}% → " +
                    "${(comparison.scenarioProbBelowFloor * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ComparisonRow(label: String, baseline: Money, scenario: Money, isBalance: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row {
            MoneyText(baseline, isBalance = isBalance)
            Text(" → ", style = MaterialTheme.typography.bodyMedium)
            MoneyText(scenario, isBalance = isBalance)
        }
    }
}

@Composable
private fun ScenarioItemRow(
    item: ScenarioItem,
    categories: List<Category>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(item.label.ifBlank { itemTypeLabel(item.type) }, style = MaterialTheme.typography.bodyLarge)
                Text(itemSummary(item, categories), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete item")
            }
        }
    }
}

private fun itemTypeLabel(type: ScenarioItemType): String = when (type) {
    ScenarioItemType.ONE_OFF -> "One-off"
    ScenarioItemType.RECURRING -> "Recurring"
    ScenarioItemType.CATEGORY_ADJUST -> "Category adjustment"
}

private fun itemSummary(item: ScenarioItem, categories: List<Category>): String = when (item.type) {
    ScenarioItemType.ONE_OFF ->
        "${item.amount.format(showPlus = true)} on ${item.date?.toShortLabel() ?: "no date"}"

    ScenarioItemType.RECURRING -> {
        val unit = item.frequency?.name?.lowercase() ?: "period"
        "${item.amount.format(showPlus = true)} every ${item.interval} $unit from ${item.date?.toShortLabel() ?: "?"}"
    }

    ScenarioItemType.CATEGORY_ADJUST -> {
        val categoryName = categories.firstOrNull { it.id == item.categoryId }?.name ?: "category"
        val sign = if (item.percent >= 0) "+" else ""
        val regimeLabel = when (item.regime) {
            Regime.TERM -> "term"
            Regime.HOLIDAY -> "holiday"
            null -> "term & holiday"
        }
        "$categoryName $sign${item.percent}% ($regimeLabel)"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenarioItemEditor(
    initial: ScenarioItem?,
    categories: List<Category>,
    accounts: List<Account>,
    onSave: (ScenarioItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var type by remember { mutableStateOf(initial?.type ?: ScenarioItemType.ONE_OFF) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var isOutflow by remember { mutableStateOf(initial?.amount?.isNegative ?: true) }
    var amountText by remember {
        mutableStateOf(initial?.amount?.abs()?.let { moneyToFieldText(it) } ?: "")
    }
    var accountId by remember { mutableStateOf(initial?.accountId) }
    var categoryId by remember { mutableStateOf(initial?.categoryId) }
    var date by remember { mutableStateOf(initial?.date ?: LocalDate.now()) }
    var endDate by remember { mutableStateOf(initial?.endDate) }
    var frequency by remember { mutableStateOf(initial?.frequency ?: Frequency.MONTHLY) }
    var interval by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var percentText by remember { mutableStateOf((initial?.percent ?: 0).toString()) }
    var regime by remember { mutableStateOf(initial?.regime) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = type == ScenarioItemType.ONE_OFF, onClick = { type = ScenarioItemType.ONE_OFF }, label = { Text("One-off") })
                FilterChip(selected = type == ScenarioItemType.RECURRING, onClick = { type = ScenarioItemType.RECURRING }, label = { Text("Recurring") })
                FilterChip(
                    selected = type == ScenarioItemType.CATEGORY_ADJUST,
                    onClick = { type = ScenarioItemType.CATEGORY_ADJUST },
                    label = { Text("Category %") },
                )
            }

            if (type != ScenarioItemType.CATEGORY_ADJUST) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isOutflow, onClick = { isOutflow = true }, label = { Text("Out") })
                    FilterChip(selected = !isOutflow, onClick = { isOutflow = false }, label = { Text("In") })
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                AccountPicker(
                    accounts = accounts,
                    selectedAccountId = accountId,
                    onSelect = { accountId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Account (optional)",
                )

                CategoryPicker(
                    categories = categories,
                    selectedCategoryId = categoryId,
                    onSelect = { categoryId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Category (optional)",
                )

                DateField(label = if (type == ScenarioItemType.ONE_OFF) "Date" else "Start date", date = date, onDateChange = { date = it })

                if (type == ScenarioItemType.RECURRING) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Frequency.entries.forEach { f ->
                            FilterChip(selected = frequency == f, onClick = { frequency = f }, label = { Text(f.name.lowercase()) })
                        }
                    }
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter { c -> c.isDigit() } },
                        label = { Text("Every N ${frequency.name.lowercase()}(s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Has an end date")
                        Switch(checked = endDate != null, onCheckedChange = { endDate = if (it) date else null })
                    }
                    if (endDate != null) {
                        DateField(label = "End date", date = endDate ?: date, onDateChange = { endDate = it })
                    }
                }
            } else {
                CategoryPicker(
                    categories = categories.filter { !it.isSystem },
                    selectedCategoryId = categoryId,
                    onSelect = { categoryId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Category",
                )
                OutlinedTextField(
                    value = percentText,
                    onValueChange = { percentText = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("Adjustment, e.g. -20 for 20% less") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = regime == Regime.TERM, onClick = { regime = Regime.TERM }, label = { Text("Term") })
                    FilterChip(selected = regime == Regime.HOLIDAY, onClick = { regime = Regime.HOLIDAY }, label = { Text("Holiday") })
                    FilterChip(selected = regime == null, onClick = { regime = null }, label = { Text("Both") })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = {
                    val amountMagnitude = Money.parse(amountText) ?: Money.ZERO
                    val signedAmount = if (isOutflow) -amountMagnitude.abs() else amountMagnitude.abs()
                    val item = ScenarioItem(
                        id = initial?.id ?: 0,
                        type = type,
                        label = label,
                        accountId = accountId,
                        categoryId = categoryId,
                        amount = if (type == ScenarioItemType.CATEGORY_ADJUST) Money.ZERO else signedAmount,
                        date = if (type == ScenarioItemType.CATEGORY_ADJUST) null else date,
                        frequency = if (type == ScenarioItemType.RECURRING) frequency else null,
                        interval = interval.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        endDate = if (type == ScenarioItemType.RECURRING) endDate else null,
                        percent = percentText.toIntOrNull() ?: 0,
                        regime = if (type == ScenarioItemType.CATEGORY_ADJUST) regime else null,
                    )
                    onSave(item)
                }) { Text("Save") }
            }
        }
    }
}

private fun moneyToFieldText(money: Money): String {
    val pounds = money.pence / 100
    val pence = money.pence % 100
    return if (pence == 0L) pounds.toString() else "$pounds.${pence.toString().padStart(2, '0')}"
}
