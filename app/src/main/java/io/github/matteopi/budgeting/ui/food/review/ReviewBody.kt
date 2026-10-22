@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Light
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.StepKind
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.food.components.FoodFormat
import io.github.matteopi.budgeting.ui.food.components.LightBadge
import io.github.matteopi.budgeting.ui.food.ingredient.FoodNumberField
import io.github.matteopi.budgeting.ui.food.ingredient.FoodTexts
import io.github.matteopi.budgeting.ui.food.ingredient.OptionPicker

/**
 * The review step of adding or editing a recipe (docs/PLAN.md §11.4): the live numbers per portion at the top, the
 * header fields, every ingredient line with its matched ingredient (or *Choose ingredient*, *Create ingredient* and
 * *Not counted*) and its amount, then the method, tips, kit and storage. One scrolling column rather than a lazy
 * list, so every field stays composed while the person types and scrolls (a recipe has a few dozen rows at most).
 */
@Composable
internal fun ReviewBody(viewModel: RecipeReviewViewModel, modifier: Modifier = Modifier) {
    val edit by viewModel.edit.collectAsStateWithLifecycle()
    val derived by viewModel.derived.collectAsStateWithLifecycle()
    val ingredients by viewModel.ingredients.collectAsStateWithLifecycle()
    val warnings by viewModel.warnings.collectAsStateWithLifecycle()
    val create by viewModel.create.collectAsStateWithLifecycle()
    var choosingFor by remember { mutableStateOf<Long?>(null) }
    // The food list's foods too, under their provisional ids, for lines that take one (docs/PLAN.md §11.12).
    val byId = remember(ingredients) { ingredients.associateBy { it.id } + ReviewFoods.ingredients }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        derived?.let { LiveNumbers(it) }
        if (warnings.isNotEmpty()) {
            SectionCard(title = "Not everything could be read") {
                warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        HeaderCard(edit = edit, viewModel = viewModel)
        IngredientsCard(edit = edit, derived = derived, byId = byId, viewModel = viewModel, onChoose = { choosingFor = it })
        StepKind.entries.forEach { kind -> StepsCard(kind = kind, edit = edit, viewModel = viewModel) }
        Spacer(Modifier.height(24.dp))
    }

    choosingFor?.let { key ->
        val line = edit.lines.firstOrNull { it.key == key }
        if (line == null) {
            choosingFor = null
        } else {
            IngredientChooserSheet(
                line = line,
                ingredients = ingredients,
                onPick = { id ->
                    viewModel.chooseIngredient(key, id)
                    choosingFor = null
                },
                onCreate = {
                    viewModel.startCreate(key)
                    choosingFor = null
                },
                onDismiss = { choosingFor = null },
            )
        }
    }
    create?.let { form -> CreateIngredientDialog(form = form, viewModel = viewModel) }
}

/** Cost, kcal, protein, salt and veg per portion as the recipe stands, and what still needs an answer. */
@Composable
private fun LiveNumbers(derived: ReviewDerived) {
    val numbers = derived.numbers
    val perPortion = numbers.nutrition.perPortion
    SectionCard(title = "Per portion, as it stands") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                FoodFormat.cost(numbers.cost.perPortion, numbers.cost.atLeast),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(ReviewTags.COST),
            )
            Text(FoodFormat.kcal(perPortion.kcal), modifier = Modifier.testTag(ReviewTags.KCAL))
            Text("${FoodFormat.grams(perPortion.protein)} protein", modifier = Modifier.testTag(ReviewTags.PROTEIN))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(ReviewTags.SALT)) {
                Text("${FoodFormat.grams(perPortion.salt)} salt")
                Spacer(Modifier.width(6.dp))
                LightBadge(numbers.nutrition.lights[Nutrient.SALT] ?: Light.GREEN)
            }
            Text("${FoodFormat.veg(numbers.veg)} veg", modifier = Modifier.testTag(ReviewTags.VEG))
        }
        val notes = buildList {
            if (derived.unmatched > 0) add(FoodFormat.count(derived.unmatched, "line") + " still need an ingredient")
            if (derived.noQuantity > 0) add(FoodFormat.count(derived.noQuantity, "line") + " have no amount")
            if (numbers.cost.missingPrice.isNotEmpty()) add(FoodFormat.missingPrices(numbers.cost.missingPrice.size))
            if (numbers.nutrition.atLeast) add(FoodFormat.missingNutrition(numbers.nutrition.unknownIngredients.size))
        }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                notes.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ReviewTags.UNMATCHED),
            )
        }
    }
}

@Composable
private fun HeaderCard(edit: ReviewEdit, viewModel: RecipeReviewViewModel) {
    SectionCard(title = "Recipe") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = edit.title,
                onValueChange = viewModel::setTitle,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodNumberField(
                    label = "Servings",
                    value = edit.servesText,
                    onValueChange = viewModel::setServes,
                    integersOnly = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = edit.prepText,
                    onValueChange = viewModel::setPrep,
                    label = { Text("Prep (min)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = edit.cookText,
                    onValueChange = viewModel::setCook,
                    label = { Text("Cook (min)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            TextLine("Style (\"Hob · one pot\")", edit.style, viewModel::setStyle)
            TextLine("Tags, separated by commas", edit.tagsText, viewModel::setTags)
            TextLine("About", edit.about, viewModel::setAbout, singleLine = false)
            TextLine("Serve with", edit.servedWith, viewModel::setServedWith)
            TextLine("Time note", edit.timeNote, viewModel::setTimeNote)
        }
    }
}

@Composable
private fun TextLine(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IngredientsCard(
    edit: ReviewEdit,
    derived: ReviewDerived?,
    byId: Map<Long, Ingredient>,
    viewModel: RecipeReviewViewModel,
    onChoose: (Long) -> Unit,
) {
    SectionCard(title = "Ingredients") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var previousGroup: String? = null
            edit.lines.forEach { line ->
                val group = line.group.trim()
                if (group.isNotEmpty() && group != previousGroup) {
                    Text(group.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                previousGroup = group
                LineEditor(line = line, derived = derived?.lines?.get(line.key), byId = byId, viewModel = viewModel, onChoose = onChoose)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            OutlinedButton(onClick = viewModel::addLine, modifier = Modifier.testTag(ReviewTags.ADD_LINE)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add a line")
            }
        }
    }
}

@Composable
private fun LineEditor(
    line: ReviewLine,
    derived: LineDerived?,
    byId: Map<Long, Ingredient>,
    viewModel: RecipeReviewViewModel,
    onChoose: (Long) -> Unit,
) {
    val key = line.key
    val ingredient = line.ingredientId?.let { byId[it] }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = line.text,
                onValueChange = { viewModel.setLineText(key, it) },
                label = { Text("Ingredient line") },
                modifier = Modifier.weight(1f).testTag(ReviewTags.line(key, "text")),
            )
            IconButton(onClick = { viewModel.deleteLine(key) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove this line")
            }
        }
        when {
            line.origin == LineOrigin.NOT_COUNTED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Not counted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag(ReviewTags.line(key, "status")),
                )
                TextButton(onClick = { viewModel.recount(key) }) { Text("Count it") }
            }
            ingredient != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val fromFoods = ReviewFoods.isFood(ingredient.id)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (fromFoods) "From the food list: ${ingredient.name}" else "→ ${ingredient.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).testTag(ReviewTags.line(key, "status")),
                    )
                    TextButton(onClick = { onChoose(key) }, modifier = Modifier.testTag(ReviewTags.line(key, "change"))) {
                        Text("Change")
                    }
                    TextButton(onClick = { viewModel.markNotCounted(key) }) { Text("Not counted") }
                }
                // A food stands in for an ingredient of the user's that only partly fits; those stay one tap away.
                val suggestions = if (fromFoods) line.suggestionIds.mapNotNull { byId[it] }.filter { !it.archived }.take(3) else emptyList()
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { suggestion ->
                            AssistChip(
                                onClick = { viewModel.chooseIngredient(key, suggestion.id) },
                                label = { Text(suggestion.name) },
                                modifier = Modifier.testTag(ReviewTags.line(key, "suggestion-${suggestion.id}")),
                            )
                        }
                    }
                }
            }
            line.text.isBlank() -> Unit
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "No ingredient yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ReviewTags.line(key, "status")),
                )
                val suggestions = line.suggestionIds.mapNotNull { byId[it] }.filter { !it.archived }.take(3)
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { suggestion ->
                            AssistChip(
                                onClick = { viewModel.chooseIngredient(key, suggestion.id) },
                                label = { Text(suggestion.name) },
                                modifier = Modifier.testTag(ReviewTags.line(key, "suggestion-${suggestion.id}")),
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onChoose(key) }, modifier = Modifier.testTag(ReviewTags.line(key, "choose"))) {
                        Text("Choose ingredient")
                    }
                    TextButton(onClick = { viewModel.startCreate(key) }, modifier = Modifier.testTag(ReviewTags.line(key, "create"))) {
                        Text("Create ingredient")
                    }
                    TextButton(
                        onClick = { viewModel.markNotCounted(key) },
                        modifier = Modifier.testTag(ReviewTags.line(key, "not-counted")),
                    ) { Text("Not counted") }
                }
            }
        }
        if (ingredient != null && derived != null) {
            AmountRow(line = line, derived = derived, unit = ingredient.unit, viewModel = viewModel)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = line.optional,
                onCheckedChange = { viewModel.setLineOptional(key, it) },
                modifier = Modifier.testTag(ReviewTags.line(key, "optional")),
            )
            Text("Optional", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The line's amount in the ingredient's unit, worked out from the text unless the person types one. */
@Composable
private fun AmountRow(line: ReviewLine, derived: LineDerived, unit: BaseUnit, viewModel: RecipeReviewViewModel) {
    val key = line.key
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FoodNumberField(
                label = "Amount (${FoodTexts.unitSymbol(unit)})",
                value = derived.amountText,
                onValueChange = { viewModel.setLineAmount(key, it) },
                isError = derived.quantityInvalid,
                modifier = Modifier.weight(1f).testTag(ReviewTags.line(key, "amount")),
            )
            if (derived.quantityOverridden) {
                TextButton(onClick = { viewModel.resetLineAmount(key) }) { Text("Use the line's amount") }
            }
        }
        when {
            derived.quantityInvalid -> Hint("The amount must be a number, such as 150.", error = true)
            derived.textGivesNoQuantity && derived.quantity == null ->
                Hint("The line doesn't say how much: type it in ${FoodTexts.unitSymbol(unit)}.", error = true)
            derived.estimated -> Hint("Estimated weight: check it against the pack.")
        }
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun kindTitle(kind: StepKind): String = when (kind) {
    StepKind.STEP -> "Method"
    StepKind.TIP -> "Tips"
    StepKind.EQUIPMENT -> "Kit"
    StepKind.STORAGE -> "Freezing and storing"
}

private fun addLabel(kind: StepKind): String = when (kind) {
    StepKind.STEP -> "Add a step"
    StepKind.TIP -> "Add a tip"
    StepKind.EQUIPMENT -> "Add kit"
    StepKind.STORAGE -> "Add a storage note"
}

/** The method steps (title, time and text) or the tips, kit or storage notes (text only). */
@Composable
private fun StepsCard(kind: StepKind, edit: ReviewEdit, viewModel: RecipeReviewViewModel) {
    val steps = edit.steps.filter { it.kind == kind }
    SectionCard(title = kindTitle(kind)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { index, step ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (kind == StepKind.STEP) {
                            Text("${index + 1}.", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = step.title,
                                onValueChange = { viewModel.setStepTitle(step.key, it) },
                                label = { Text("Step title") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag(ReviewTags.step(step.key, "title")),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = step.time,
                                onValueChange = { viewModel.setStepTime(step.key, it) },
                                label = { Text("Time") },
                                singleLine = true,
                                modifier = Modifier.width(96.dp).testTag(ReviewTags.step(step.key, "time")),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        IconButton(onClick = { viewModel.deleteStep(step.key) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove this ${if (kind == StepKind.STEP) "step" else "note"}")
                        }
                    }
                    OutlinedTextField(
                        value = step.body,
                        onValueChange = { viewModel.setStepBody(step.key, it) },
                        label = { Text(if (kind == StepKind.STEP) "What to do" else "Text") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().testTag(ReviewTags.step(step.key, "body")),
                    )
                }
            }
            if (kind == StepKind.STEP && steps.isNotEmpty()) {
                Hint("Start a paragraph with \"Ready when:\" for a cue, or \"If …:\" for what to do when it goes wrong.")
            }
            OutlinedButton(onClick = { viewModel.addStep(kind) }, modifier = Modifier.testTag(ReviewTags.add(kind))) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(addLabel(kind))
            }
        }
    }
}

/** Choosing the ingredient of one line: its suggestions first, then every ingredient, searchable, then the food list's matches. */
@Composable
private fun IngredientChooserSheet(
    line: ReviewLine,
    ingredients: List<Ingredient>,
    onPick: (Long) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val live = ingredients.filter { !it.archived }
    val suggested = line.suggestionIds.mapNotNull { id -> live.firstOrNull { it.id == id } }
    val ordered = (suggested + live.sortedBy { it.name.lowercase() }).distinctBy { it.id }
    val shown = ordered.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    // What the food list has for the search, for a line none of the user's ingredients fits (docs/PLAN.md §11.12).
    val foods = remember(query) { if (query.isBlank()) emptyList() else FoodDatabase.search(query).take(MAX_FOODS) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose the ingredient", style = MaterialTheme.typography.titleLarge)
            Text(line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search ingredients") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ReviewTags.CHOOSER_SEARCH),
            )
            TextButton(onClick = onCreate) { Text("Create a new ingredient") }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(shown, key = { it.id }) { ingredient ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onPick(ingredient.id) }
                            .padding(vertical = 8.dp)
                            .testTag(ReviewTags.chooserRow(ingredient.id)),
                    ) {
                        Text(ingredient.name, style = MaterialTheme.typography.bodyLarge)
                        val note = if (ingredient.id in line.suggestionIds) "Suggested · " else ""
                        Text(
                            note + FoodTexts.shelfName(ingredient.shelf),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
                if (foods.isNotEmpty()) {
                    item(key = "foods") {
                        Text(
                            "From the food list",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(foods, key = { "food-${it.key}" }) { food ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { onPick(ReviewFoods.idOf(food)) }
                                .padding(vertical = 8.dp)
                                .testTag(ReviewTags.chooserFood(food.key)),
                        ) {
                            Text(food.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Added to your ingredients when you save · ${FoodTexts.shelfName(food.ingredient.shelf)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** How many of the food list's foods the chooser shows for a search. */
private const val MAX_FOODS = 8

/** The short form that creates an ingredient for a line and puts it on the line (docs/PLAN.md §11.4). */
@Composable
private fun CreateIngredientDialog(form: CreateIngredientForm, viewModel: RecipeReviewViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelCreate,
        title = { Text("New ingredient") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { text -> viewModel.updateCreate { it.copy(name = text) } },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ReviewTags.CREATE_NAME),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.unit == BaseUnit.G,
                        onClick = { viewModel.updateCreate { it.copy(unit = BaseUnit.G) } },
                        label = { Text("Weighed (g)") },
                        modifier = Modifier.testTag(ReviewTags.CREATE_UNIT_G),
                    )
                    FilterChip(
                        selected = form.unit == BaseUnit.ML,
                        onClick = { viewModel.updateCreate { it.copy(unit = BaseUnit.ML) } },
                        label = { Text("Measured (ml)") },
                        modifier = Modifier.testTag(ReviewTags.CREATE_UNIT_ML),
                    )
                }
                FoodNumberField(
                    label = "Pack size (${FoodTexts.unitSymbol(form.unit)})",
                    value = form.packSizeText,
                    onValueChange = { text -> viewModel.updateCreate { it.copy(packSizeText = text) } },
                    modifier = Modifier.fillMaxWidth().testTag(ReviewTags.CREATE_PACK_SIZE),
                )
                OutlinedTextField(
                    value = form.packLabel,
                    onValueChange = { text -> viewModel.updateCreate { it.copy(packLabel = text) } },
                    label = { Text("Pack (\"225 g block\")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ReviewTags.CREATE_PACK_LABEL),
                )
                FoodNumberField(
                    label = "Pack price (optional)",
                    value = form.priceText,
                    onValueChange = { text -> viewModel.updateCreate { it.copy(priceText = text) } },
                    prefix = "£",
                    modifier = Modifier.fillMaxWidth().testTag(ReviewTags.CREATE_PRICE),
                )
                OptionPicker(
                    label = "Shelf",
                    selected = form.shelf,
                    options = Shelf.entries,
                    optionName = FoodTexts::shelfName,
                    onSelect = { shelf -> viewModel.updateCreate { it.copy(shelf = shelf) } },
                )
                OptionPicker(
                    label = "Counts towards 5 a day",
                    selected = form.vegKind,
                    options = VegKind.entries,
                    optionName = FoodTexts::vegKindName,
                    onSelect = { kind -> viewModel.updateCreate { it.copy(vegKind = kind) } },
                )
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::submitCreate, modifier = Modifier.testTag(ReviewTags.CREATE_SUBMIT)) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelCreate, modifier = Modifier.testTag(ReviewTags.CREATE_CANCEL)) {
                Text("Cancel")
            }
        },
    )
}
