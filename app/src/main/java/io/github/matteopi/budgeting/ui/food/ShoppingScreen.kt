@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.ShoppingFreeLine
import io.github.matteopi.budgeting.core.recipes.ShoppingLine
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.food.components.ServingsStepper
import io.github.matteopi.budgeting.ui.food.ingredient.FoodNumberField
import io.github.matteopi.budgeting.ui.food.ingredient.FoodTexts
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientFormRules
import io.github.matteopi.budgeting.ui.food.ingredient.ShoppingTexts
import io.github.matteopi.budgeting.ui.food.scan.PackPhotoButton
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton
import kotlinx.coroutines.launch

/** Test tags of the Shopping tab. */
object ShoppingTags {
    const val HEADER = "shopping-header"
    const val SCAN = "shopping-scan"
    const val MEALS = "shopping-meals"
    const val ADD_ITEM = "shopping-add-item"
    const val ADD_TEXT = "shopping-add-text"
    const val ADD_QUANTITY = "shopping-add-quantity"
    const val ADD_CONFIRM = "shopping-add-confirm"

    fun line(ingredientId: Long) = "shopping-line-$ingredientId"

    fun lineQuantity(ingredientId: Long) = "shopping-line-$ingredientId-quantity"

    fun lineCheck(ingredientId: Long) = "shopping-line-$ingredientId-check"

    fun lineMenu(ingredientId: Long) = "shopping-line-$ingredientId-menu"

    fun freeText(entryId: Long) = "shopping-free-$entryId"

    fun freeTextCheck(entryId: Long) = "shopping-free-$entryId-check"

    fun pick(ingredientId: Long) = "shopping-pick-$ingredientId"
}

/**
 * The Shopping tab (docs/PLAN.md §11.6): what to buy for the recipes on the list plus the extra items, summed per
 * ingredient and grouped by shelf in shop order, with packs and an estimated cost. Lines tick off; *I have it* takes
 * one off the bill; the recipes on the list can change servings or go.
 */
@Composable
fun ShoppingScreen(
    modifier: Modifier = Modifier,
    viewModel: ShoppingViewModel = viewModel(factory = ShoppingViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var servingsFor by remember { mutableStateOf<ShoppingRecipeUi?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Shopping list") },
                navigationIcon = { SectionSwitchButton(AppSection.RECIPES) },
                actions = {
                    PackPhotoButton(
                        label = "Scan to tick",
                        tag = ShoppingTags.SCAN,
                        enabled = true,
                        onPhoto = { uri ->
                            viewModel.scanToTick(context, uri) { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                        },
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear ticked") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.clearTicked()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear list") },
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                // Material clears the extended button's text from accessibility, so the icon carries its name.
                icon = { Icon(Icons.Default.Add, contentDescription = "Add item") },
                text = { Text("Add item") },
                modifier = Modifier.testTag(ShoppingTags.ADD_ITEM),
            )
        },
    ) { innerPadding ->
        val list = state.list
        when {
            state.loading -> Unit
            list == null || state.isEmpty -> EmptyState(
                message = "Your shopping list is empty — add a recipe from its page, or tap Add item.",
                modifier = Modifier.padding(innerPadding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") {
                    SectionCard {
                        Text(
                            ShoppingTexts.headerLine(list.header),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag(ShoppingTags.HEADER),
                        )
                        ShoppingTexts.mealsLine(list.header, state.recipes.isNotEmpty())?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(ShoppingTags.MEALS))
                        }
                        ShoppingTexts.unpricedLine(list.header)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.recipes.isNotEmpty()) {
                    item(key = "recipes") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.recipes.forEach { recipe ->
                                InputChip(
                                    selected = false,
                                    onClick = { servingsFor = recipe },
                                    label = { Text("${recipe.name} · ${ShoppingTexts.servings(recipe.servings)}") },
                                    trailingIcon = {
                                        IconButton(onClick = { viewModel.removeEntry(recipe.entryId) }, modifier = Modifier.size(24.dp)) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Take ${recipe.name} off the list",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                list.sections.forEach { section ->
                    item(key = "shelf-${section.shelf.name}") {
                        Text(
                            FoodTexts.shelfName(section.shelf),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(section.lines, key = { "line-${it.ingredientId}" }) { line ->
                        LineRow(
                            line = line,
                            neededFor = ShoppingTexts.neededFor(line, state.recipeNames),
                            onChecked = { viewModel.setChecked(line.ingredientId, it) },
                            onHaveIt = { viewModel.setHaveIt(line.ingredientId, it) },
                        )
                    }
                }
                if (list.freeText.isNotEmpty()) {
                    item(key = "free-heading") {
                        Text(
                            "Other items",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(list.freeText, key = { "free-${it.entryId}" }) { line ->
                        FreeTextRow(
                            line = line,
                            onChecked = { viewModel.setFreeTextChecked(line.entryId, it) },
                            onRemove = { viewModel.removeEntry(line.entryId) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear the whole list?",
            text = "Every recipe and item comes off the list.",
            confirmLabel = "Clear list",
            onConfirm = {
                confirmClear = false
                viewModel.clearList()
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (adding) {
        AddItemDialog(
            ingredients = state.ingredients,
            onAddIngredient = { id, quantity ->
                viewModel.addIngredient(id, quantity)
                adding = false
            },
            onAddText = { text ->
                viewModel.addFreeText(text)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    servingsFor?.let { recipe ->
        ServingsDialog(
            recipe = recipe,
            onSave = { servings ->
                viewModel.setServings(recipe.entryId, servings)
                servingsFor = null
            },
            onDismiss = { servingsFor = null },
        )
    }
}

@Composable
private fun LineRow(line: ShoppingLine, neededFor: String?, onChecked: (Boolean) -> Unit, onHaveIt: (Boolean) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val done = line.checked || line.haveIt
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ShoppingTags.line(line.ingredientId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = line.checked,
            onCheckedChange = onChecked,
            modifier = Modifier.testTag(ShoppingTags.lineCheck(line.ingredientId)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                line.name,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                ShoppingTexts.quantityLine(line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ShoppingTags.lineQuantity(line.ingredientId)),
            )
            neededFor?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ShoppingTexts.costText(line)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(ShoppingTags.lineMenu(line.ingredientId))) {
                Icon(Icons.Default.MoreVert, contentDescription = "More for ${line.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (line.haveIt) "I don't have it" else "I have it") },
                    onClick = {
                        menuOpen = false
                        onHaveIt(!line.haveIt)
                    },
                )
            }
        }
    }
}

@Composable
private fun FreeTextRow(line: ShoppingFreeLine, onChecked: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ShoppingTags.freeText(line.entryId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = line.checked,
            onCheckedChange = onChecked,
            modifier = Modifier.testTag(ShoppingTags.freeTextCheck(line.entryId)),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (line.checked) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove ${line.text}")
        }
    }
}

/**
 * Adding an extra item: type what it is, then either pick one of the matching ingredients (with an amount, or a
 * whole pack when left blank) or keep it as text.
 */
@Composable
private fun AddItemDialog(
    ingredients: List<Ingredient>,
    onAddIngredient: (Long, Double?) -> Unit,
    onAddText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Ingredient?>(null) }
    var quantityText by remember { mutableStateOf("") }
    val chosen = picked
    val matches = if (text.isBlank()) emptyList() else ingredients.filter { it.name.contains(text.trim(), ignoreCase = true) }.take(6)
    val quantity = IngredientFormRules.parseNumber(quantityText)
    val quantityBad = quantityText.isNotBlank() && (quantity == null || quantity <= 0.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to the list") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chosen == null) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Item") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(ShoppingTags.ADD_TEXT),
                    )
                    matches.forEach { ingredient ->
                        Text(
                            ingredient.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { picked = ingredient }
                                .padding(vertical = 12.dp)
                                .testTag(ShoppingTags.pick(ingredient.id)),
                        )
                        HorizontalDivider()
                    }
                    if (matches.isNotEmpty()) {
                        Text(
                            "Pick an ingredient to count it with your recipes, or add it as it is.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(chosen.name, style = MaterialTheme.typography.titleMedium)
                    FoodNumberField(
                        label = "How much (${FoodTexts.unitSymbol(chosen.unit)}), blank for one pack",
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        isError = quantityBad,
                        modifier = Modifier.fillMaxWidth().testTag(ShoppingTags.ADD_QUANTITY),
                    )
                    TextButton(onClick = { picked = null }) { Text("Choose something else") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (chosen != null) onAddIngredient(chosen.id, quantity) else onAddText(text.trim())
                },
                enabled = if (chosen != null) !quantityBad else text.isNotBlank(),
                modifier = Modifier.testTag(ShoppingTags.ADD_CONFIRM),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ServingsDialog(recipe: ShoppingRecipeUi, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var servings by remember { mutableStateOf(recipe.servings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recipe.name) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Servings", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ServingsStepper(servings = servings, onServingsChange = { servings = it })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(servings) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
