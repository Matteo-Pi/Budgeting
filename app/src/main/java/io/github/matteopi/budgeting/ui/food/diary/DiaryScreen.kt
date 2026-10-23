@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.Batches
import io.github.matteopi.budgeting.core.nutrition.IntakeKey
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.AppIcons
import io.github.matteopi.budgeting.ui.components.DatePickerDialogFor
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton
import java.time.LocalDate

/**
 * The Diary tab (docs/PLAN.md §14.5): the day (previous, next, or picked), its summary against the targets with
 * water, the kitchen's batches ready to eat with *Ate one*, and the meals with their rows. A row opens a sheet to
 * change its amount or meal, or delete it (with Undo).
 */
@Composable
fun DiaryScreen(
    onLogFood: (date: LocalDate, meal: MealSlot?, batchId: Long?) -> Unit,
    onAllNutrients: (LocalDate) -> Unit,
    onTrends: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiaryViewModel = viewModel(factory = DiaryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<LogItem?>(null) }
    var picking by remember { mutableStateOf(false) }

    // Collected rather than keyed on the message, so clearing it does not cancel its snackbar; a newer message replaces it.
    LaunchedEffect(Unit) {
        viewModel.message.filterNotNull().collectLatest { shown ->
            viewModel.messageShown()
            val result = snackbarHostState.showSnackbar(
                message = shown.text,
                actionLabel = if (shown.undo != null) "Undo" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) shown.undo?.invoke()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Diary") },
                navigationIcon = { SectionSwitchButton(AppSection.RECIPES) },
                actions = {
                    IconButton(onClick = onTrends) { Icon(AppIcons.BarChart, contentDescription = "Trends") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Food settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onLogFood(state.date, null, null) },
                icon = { Icon(Icons.Default.Add, contentDescription = "Log food") },
                text = { Text("Log food") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "day") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::previousDay) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous day") }
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).clickable { picking = true }.padding(vertical = 8.dp).testTag(DiaryTags.DAY),
                    )
                    if (state.date != state.today) TextButton(onClick = { viewModel.setDate(state.today) }) { Text("Today") }
                    IconButton(onClick = viewModel::nextDay) { Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next day") }
                }
            }
            item(key = "summary") { SummaryCard(state, viewModel::addGlass, viewModel::removeGlass) { onAllNutrients(state.date) } }
            if (state.ready.isNotEmpty() && state.date == state.today) {
                item(key = "ready") {
                    ReadyToEat(state.ready.take(6), onAteOne = viewModel::ateOne, onOpen = { onLogFood(state.date, null, it) })
                }
            }
            items(state.meals, key = { "meal-${it.meal.name}" }) { section ->
                MealCard(section, onAdd = { onLogFood(state.date, section.meal, null) }, onRow = { editing = it })
            }
        }
    }

    editing?.let { row ->
        RowSheet(
            row = row,
            onDismiss = { editing = null },
            onSave = { amount, meal ->
                viewModel.update(row, amount, meal)
                editing = null
            },
            onDelete = {
                viewModel.delete(row)
                editing = null
            },
        )
    }
    if (picking) {
        DatePickerDialogFor(initialDate = state.date, maxDate = null, onDateChange = viewModel::setDate, onDismiss = { picking = false })
    }
}

@Composable
private fun SummaryCard(state: DiaryUiState, onAddGlass: () -> Unit, onRemoveGlass: () -> Unit, onAllNutrients: () -> Unit) {
    val summary = state.summary
    val kcal = summary.of(IntakeKey.KCAL)
    Card(Modifier.fillMaxWidth().testTag(DiaryTags.SUMMARY)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    kcalText(kcal.amount),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag(DiaryTags.KCAL),
                )
                Text(" of ${kcalText(kcal.target)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 3.dp))
            }
            val left = kcal.target - kcal.amount
            Text(
                if (left >= 0) "${Quantities.roundHalfUp(left)} kcal left" else "${Quantities.roundHalfUp(-left)} kcal over",
                style = MaterialTheme.typography.bodyMedium,
                color = if (left >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            LinearProgressIndicator(
                progress = { kcal.fraction.toFloat().coerceIn(0f, 1f) },
                color = progressColor(kcal),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            NutrientBar("Protein", summary.of(IntakeKey.PROTEIN))
            NutrientBar("Carbs", summary.of(IntakeKey.CARBS))
            NutrientBar("Fat", summary.of(IntakeKey.FAT))
            Text(
                "Fibre ${gramsText(summary.total[IntakeKey.FIBRE])} · Sugars ${gramsText(summary.total[IntakeKey.SUGARS])} · " +
                    "Salt ${Quantities.plain(summary.total[IntakeKey.SALT], 1)} g",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "5 a day: ${Quantities.plain(summary.vegPortions, 1)} · Cost ${penceText(summary.costPence)}" +
                    if (summary.unpriced > 0) " (${summary.unpriced} without a price)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "Water ${summary.waterMl} of ${state.prefs.waterGoalMl} ml",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag(DiaryTags.WATER),
                )
                if (summary.waterMl > 0) TextButton(onClick = onRemoveGlass) { Text("− glass") }
                OutlinedButton(onClick = onAddGlass, modifier = Modifier.testTag(DiaryTags.ADD_GLASS)) { Text("+ glass") }
            }
            TextButton(onClick = onAllNutrients, modifier = Modifier.testTag(DiaryTags.ALL_NUTRIENTS)) { Text("All nutrients") }
        }
    }
}

@Composable
private fun ReadyToEat(batches: List<Batch>, onAteOne: (Long) -> Unit, onOpen: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Ready to eat", style = MaterialTheme.typography.titleMedium)
            batches.forEach { batch ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).testTag(DiaryTags.ready(batch.id)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).clickable { onOpen(batch.id) }) {
                        Text(batch.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${DiaryMapping.leftText(batch)} · ${kcalText(batch.intake.kcal)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { onAteOne(batch.id) }) { Text("Ate one") }
                }
            }
        }
    }
}

@Composable
private fun MealCard(section: MealSection, onAdd: () -> Unit, onRow: (LogItem) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(DiaryTags.meal(section.meal.name))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(section.meal.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (section.rows.isNotEmpty()) Text(kcalText(section.kcal), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onAdd) { Text("Add") }
            }
            if (section.rows.isEmpty()) {
                Text("Nothing yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            section.rows.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onRow(row.item) }
                        .padding(start = if (row.isSide) 20.dp else 0.dp, top = 6.dp, bottom = 6.dp)
                        .testTag(DiaryTags.row(row.item.id)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (row.isSide) "+ " else "") + row.item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(DiaryMapping.detail(row.item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(kcalText(row.item.intake.kcal), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** A row's sheet: its amount (portions in halves, or grams), its meal, and *Delete*. */
@Composable
private fun RowSheet(row: LogItem, onDismiss: () -> Unit, onSave: (Double, MealSlot) -> Unit, onDelete: () -> Unit) {
    var amount by remember(row.id) { mutableStateOf(row.amount) }
    var text by remember(row.id) { mutableStateOf(Quantities.plain(row.amount, 1)) }
    var meal by remember(row.id) { mutableStateOf(row.meal) }
    val portions = row.kind == LogKind.BATCH || row.kind == LogKind.RECIPE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    portions -> HalfStepper(amount, { amount = it }, "portions", text = Batches.portionsText(amount))
                    row.kind == LogKind.FOOD -> OutlinedTextField(
                        value = text,
                        onValueChange = { value ->
                            text = value
                            value.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { amount = it }
                        },
                        label = { Text("Amount (g or ml)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.testTag(DiaryTags.ROW_AMOUNT),
                    )
                    else -> Text(kcalText(row.intake.kcal), style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSlot.entries.forEach { slot ->
                        FilterChip(selected = meal == slot, onClick = { meal = slot }, label = { Text(slot.label) })
                    }
                }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(amount, meal) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
