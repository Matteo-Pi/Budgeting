@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.nutrition.Batches
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.core.nutrition.Sides
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.ui.food.diary.DiaryMapping
import io.github.matteopi.budgeting.ui.food.diary.DiaryTags
import io.github.matteopi.budgeting.ui.food.diary.HalfStepper
import io.github.matteopi.budgeting.ui.food.diary.gramsText
import io.github.matteopi.budgeting.ui.food.diary.kcalText
import io.github.matteopi.budgeting.ui.food.diary.penceText
import io.github.matteopi.budgeting.ui.food.diary.shortDay

/**
 * *Log food* (docs/PLAN.md §14.5): pick from the kitchen, a recipe, a food or the quick box, set the amount (and the
 * sides of a portion), the meal, then *Save*. [onDone] gets a short message ("Logged Chilli"); [closeIcon] shows a
 * cross instead of the back arrow (the quick log pop-up).
 */
@Composable
fun LogFoodScreen(
    epochDay: Long?,
    meal: String?,
    batchId: Long?,
    recipeId: Long?,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
    closeIcon: Boolean = false,
    viewModel: LogFoodViewModel = viewModel(factory = LogFoodViewModel.factory(epochDay, meal, batchId, recipeId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val f = state.form

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (f.date == state.today) "Log food" else "Log food · ${shortDay(f.date)}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (closeIcon) Icon(Icons.Default.Close, contentDescription = "Close") else Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).testTag(DiaryTags.LOG_TOTAL)) {
                        Text(kcalText(state.total.intake.kcal), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${gramsText(state.total.intake.protein)} protein · ${penceText(state.total.costPence)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = { viewModel.save(onDone) }, enabled = state.canSave, modifier = Modifier.testTag(DiaryTags.LOG_SAVE)) { Text("Save") }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "meal") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSlot.entries.forEach { slot ->
                        FilterChip(selected = f.meal == slot, onClick = { viewModel.setMeal(slot) }, label = { Text(slot.label) })
                    }
                }
            }
            val pick = f.pick
            if (pick == null) {
                item(key = "tabs") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LogTab.entries.forEach { tab ->
                            FilterChip(selected = f.tab == tab, onClick = { viewModel.setTab(tab) }, label = { Text(tab.label) })
                        }
                    }
                }
                when (f.tab) {
                    LogTab.KITCHEN -> {
                        if (state.batches.isEmpty()) {
                            item(key = "no-batches") {
                                Text(
                                    "Nothing in the kitchen. Tap I cooked on the Kitchen tab, or I cooked this on a recipe, to add a batch.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        items(state.batches, key = { "b${it.id}" }) { batch ->
                            PickRow(
                                title = batch.name,
                                detail = "${DiaryMapping.leftText(batch)} · ${kcalText(batch.intake.kcal)} a portion",
                                tag = DiaryTags.pick("b${batch.id}"),
                            ) { viewModel.pickBatch(batch) }
                        }
                    }
                    LogTab.RECIPES -> {
                        item(key = "search") { SearchField(f.query, viewModel::setQuery, "Search recipes") }
                        items(state.recipes, key = { "r${it.id}" }) { recipe ->
                            PickRow(title = recipe.title, detail = "Serves ${recipe.serves}", tag = DiaryTags.pick("r${recipe.id}")) { viewModel.pickRecipe(recipe) }
                        }
                    }
                    LogTab.FOODS -> {
                        item(key = "search") { SearchField(f.query, viewModel::setQuery, "Search foods") }
                        items(state.foods, key = { it.key }) { option ->
                            PickRow(title = option.ingredient.name, detail = option.detail, tag = DiaryTags.pick(option.key)) { viewModel.pickFood(option) }
                        }
                    }
                    LogTab.QUICK -> item(key = "quick") { QuickBox(state, viewModel) }
                }
            } else {
                item(key = "picked") { Picked(state, viewModel) }
                if (pick is Pick.FromBatch || pick is Pick.FromRecipe) {
                    item(key = "sides") { SidesCard(state, viewModel) }
                }
            }
        }
    }

    if (f.sideSearch != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeSideSearch,
            title = { Text("Add a side") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SearchField(f.sideSearch, viewModel::setSideSearch, "Search foods")
                    state.sideFoods.forEach { option ->
                        PickRow(title = option.ingredient.name, detail = option.detail, tag = DiaryTags.side(option.key)) { viewModel.addSide(option) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeSideSearch) { Text("Close") } },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(DiaryTags.SEARCH),
    )
}

@Composable
private fun PickRow(title: String, detail: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag(tag)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun QuickBox(state: LogFoodUiState, viewModel: LogFoodViewModel) {
    val f = state.form
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = f.quickText,
            onValueChange = viewModel::setQuickText,
            label = { Text("What did you eat? (\"3 slices of toast\")") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(DiaryTags.QUICK_TEXT),
        )
        val quick = state.quick
        if (quick != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(quick.ingredient.name, style = MaterialTheme.typography.bodyLarge)
                        val kcal = quick.ingredient.per100.kcal?.let { kcalText(it * quick.amount.amount / 100) } ?: "kcal unknown"
                        Text("${quick.amount.text} · $kcal", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { viewModel.useQuick(quick) }) { Text("Use this") }
                }
            }
        } else if (f.quickText.isNotBlank()) {
            Text("No food matches that yet. Type its numbers below.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Or type the numbers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = f.quickName,
            onValueChange = { viewModel.setQuickField(name = it) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(DiaryTags.QUICK_NAME),
        )
        NumberField(f.quickKcal, { viewModel.setQuickField(kcal = it) }, "kcal", DiaryTags.QUICK_KCAL)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(f.quickProtein, { viewModel.setQuickField(protein = it) }, "Protein g", DiaryTags.QUICK_PROTEIN, Modifier.weight(1f))
            NumberField(f.quickCarbs, { viewModel.setQuickField(carbs = it) }, "Carbs g", DiaryTags.QUICK_CARBS, Modifier.weight(1f))
            NumberField(f.quickFat, { viewModel.setQuickField(fat = it) }, "Fat g", DiaryTags.QUICK_FAT, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, tag: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.testTag(tag),
    )
}

@Composable
private fun Picked(state: LogFoodUiState, viewModel: LogFoodViewModel) {
    val f = state.form
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = when (val pick = f.pick) {
                    is Pick.FromBatch -> pick.batch.name
                    is Pick.FromRecipe -> pick.recipe.shortTitle ?: pick.recipe.title
                    is Pick.FromFood -> pick.ingredient.name
                    null -> ""
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::clearPick) { Text("Change") }
            }
            when (val pick = f.pick) {
                is Pick.FromBatch -> {
                    Text(DiaryMapping.leftText(pick.batch), style = MaterialTheme.typography.bodySmall)
                    HalfStepper(f.portions, viewModel::setPortions, "portions", max = pick.batch.left.coerceAtLeast(0.5), text = Batches.portionsText(f.portions))
                }
                is Pick.FromRecipe -> HalfStepper(f.portions, viewModel::setPortions, "portions", text = Batches.portionsText(f.portions))
                is Pick.FromFood -> FoodAmount(pick, f.amount, viewModel)
                null -> Unit
            }
        }
    }
}

@Composable
private fun FoodAmount(pick: Pick.FromFood, amount: Double, viewModel: LogFoodViewModel) {
    val ingredient = pick.ingredient
    val unit = if (ingredient.unit == io.github.matteopi.budgeting.core.recipes.BaseUnit.ML) "ml" else "g"
    var text by remember(ingredient.id, pick.foodKey) { mutableStateOf(Quantities.plain(amount, 1)) }
    LaunchedEffect(amount) {
        if (text.replace(',', '.').toDoubleOrNull() != amount) text = Quantities.plain(amount, 1)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                value.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let(viewModel::setAmount)
            },
            label = { Text("Amount ($unit)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).testTag(DiaryTags.AMOUNT),
        )
        val step = LogFoodViewModel.stepOf(ingredient)
        HalfStepper(amount, viewModel::setAmount, "of it", step = step, min = step, max = 10_000.0, text = Sides.describe(ingredient, amount))
    }
}

@Composable
private fun SidesCard(state: LogFoodUiState, viewModel: LogFoodViewModel) {
    val f = state.form
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("On the side", style = MaterialTheme.typography.titleMedium)
            if (state.suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.suggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = { viewModel.addSide(suggestion) },
                            label = { Text("+ ${suggestion.ingredient.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag(DiaryTags.side(LogFoodViewModel.keyOf(suggestion.ingredient, suggestion.foodKey))),
                        )
                    }
                }
            }
            f.sides.forEach { side ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(side.ingredient.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
                    val step = LogFoodViewModel.stepOf(side.ingredient)
                    HalfStepper(
                        side.amount,
                        { viewModel.setSideAmount(side.key, it) },
                        side.ingredient.name,
                        step = step,
                        min = step,
                        max = 5_000.0,
                        text = Sides.describe(side.ingredient, side.amount),
                    )
                    IconButton(onClick = { viewModel.removeSide(side.key) }) { Icon(Icons.Default.Close, contentDescription = "Remove ${side.ingredient.name}") }
                }
            }
            OutlinedButton(onClick = viewModel::openSideSearch) { Text("Add a side") }
        }
    }
}
