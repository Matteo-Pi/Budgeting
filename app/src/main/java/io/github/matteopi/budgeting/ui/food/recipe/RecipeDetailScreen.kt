@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food.recipe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.BreakdownMetric
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Light
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.ParagraphKind
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeNumbers
import io.github.matteopi.budgeting.core.recipes.RecipeStep
import io.github.matteopi.budgeting.core.recipes.RecipeSteps
import io.github.matteopi.budgeting.core.recipes.TrafficLights
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.core.recipes.VegSummary
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.food.components.FoodFormat
import io.github.matteopi.budgeting.ui.food.components.LightBadge
import io.github.matteopi.budgeting.ui.food.components.NumberChip
import io.github.matteopi.budgeting.ui.food.components.RatingPicker
import io.github.matteopi.budgeting.ui.food.components.ServingsStepper
import io.github.matteopi.budgeting.ui.food.components.VegDots
import io.github.matteopi.budgeting.ui.food.timers.CustomTimerButton
import io.github.matteopi.budgeting.ui.food.timers.StepTimerChips
import io.github.matteopi.budgeting.ui.food.timers.TimerStarter
import io.github.matteopi.budgeting.ui.food.timers.TimersBar
import io.github.matteopi.budgeting.ui.food.timers.rememberTimerStarter
import io.github.matteopi.budgeting.core.nutrition.IntakeKey
import io.github.matteopi.budgeting.core.nutrition.Micro
import io.github.matteopi.budgeting.ui.food.diary.BatchSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of the recipe screen. */
object RecipeDetailTags {
    const val COST = "recipe-cost"
    const val KCAL = "recipe-kcal"
    const val COOK = "recipe-cook"
    const val ADD_TO_LIST = "recipe-add-to-list"
    const val NOTES = "recipe-notes"
    const val OPTIONAL_SWITCH = "recipe-optional-switch"
    const val PER_100 = "recipe-per-100"
    const val MENU = "recipe-menu"
    const val COPY = "recipe-copy"
    const val VEG_TOTAL = "recipe-veg-total"
    const val VEG_EFFECTIVE = "recipe-veg-effective"
    const val ADDED_SUGARS = "recipe-added-sugars"
    const val COOKED = "recipe-cooked"
    const val LOG_PORTION = "recipe-log-portion"
    const val VITAMINS = "recipe-vitamins"

    fun line(index: Int) = "recipe-line-$index"

    fun step(index: Int) = "recipe-step-$index"
}

/**
 * The recipe screen (docs/PLAN.md §11.8): header, *Cook* and *Add to shopping list*, the ingredients scaled to the
 * servings on the stepper, the method with its cue and rescue boxes, kit, tips and storage, then the numbers (cost,
 * the nutrition label with % RI and traffic lights, veg and where each number comes from), the rating and notes.
 */
@Composable
fun RecipeDetailScreen(
    recipeId: Long,
    onBack: () -> Unit,
    onCook: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onLogPortion: (Long) -> Unit,
    onOpenKitchen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipeDetailViewModel = viewModel(key = "recipe-$recipeId", factory = RecipeDetailViewModel.factory(recipeId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var addingToList by remember { mutableStateOf(false) }
    var cooking by remember { mutableStateOf(false) }
    val recipe = state.recipe
    val say: (String) -> Unit = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
    val startTimer = rememberTimerStarter(recipeId, recipe?.shortTitle ?: recipe?.title ?: "Recipe", say)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(recipe?.shortTitle ?: recipe?.title ?: "Recipe", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (recipe != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(RecipeDetailTags.MENU)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = {
                                        menuOpen = false
                                        onEdit(recipe.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy recipe") },
                                    onClick = {
                                        menuOpen = false
                                        val text = viewModel.shareText() ?: return@DropdownMenuItem
                                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                                        clipboard?.setPrimaryClip(ClipData.newPlainText(recipe.title, text))
                                        say("Recipe copied. Paste it into Add recipe, or into a chat.")
                                    },
                                    modifier = Modifier.testTag(RecipeDetailTags.COPY),
                                )
                                DropdownMenuItem(
                                    text = { Text("Share as text") },
                                    onClick = {
                                        menuOpen = false
                                        val text = viewModel.shareText() ?: return@DropdownMenuItem
                                        val send = Intent(Intent.ACTION_SEND)
                                            .setType("text/plain")
                                            .putExtra(Intent.EXTRA_SUBJECT, recipe.title)
                                            .putExtra(Intent.EXTRA_TEXT, text)
                                        context.startActivity(Intent.createChooser(send, "Share recipe"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Archive") },
                                    onClick = {
                                        menuOpen = false
                                        confirmArchive = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { TimersBar(onMessage = say) },
    ) { innerPadding ->
        val numbers = state.numbers
        when {
            state.loading -> Unit
            state.missing || recipe == null || numbers == null -> EmptyState(
                message = "This recipe is no longer there.",
                modifier = Modifier.padding(innerPadding),
            )
            else -> RecipeBody(
                recipe = recipe,
                state = state,
                numbers = numbers,
                viewModel = viewModel,
                onCook = { onCook(recipe.id) },
                onAddToList = { addingToList = true },
                onCooked = { cooking = true },
                onLogPortion = { onLogPortion(recipe.id) },
                onStartTimer = startTimer,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
        }
    }

    if (confirmArchive) {
        ConfirmDialog(
            title = "Archive this recipe?",
            text = "It leaves your recipes, Compare and the shopping list. Its ingredients stay.",
            confirmLabel = "Archive",
            onConfirm = {
                confirmArchive = false
                viewModel.archive(onDone = onBack)
            },
            onDismiss = { confirmArchive = false },
        )
    }
    if (cooking && recipe != null) {
        BatchSheet(recipe, today = java.time.LocalDate.now(), onDismiss = { cooking = false }) { servings, portions, freezer, madeOn ->
            cooking = false
            viewModel.cook(servings, portions, freezer, madeOn) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar("${recipe.shortTitle ?: recipe.title} is in the kitchen", actionLabel = "Kitchen")
                    if (result == SnackbarResult.ActionPerformed) onOpenKitchen()
                }
            }
        }
    }
    if (addingToList && recipe != null) {
        AddToListDialog(
            recipe = recipe,
            onAdd = { servings ->
                addingToList = false
                viewModel.addToShoppingList(servings) {
                    scope.launch { snackbarHostState.showSnackbar("Added to the shopping list") }
                }
            },
            onDismiss = { addingToList = false },
        )
    }
}

@Composable
private fun RecipeBody(
    recipe: Recipe,
    state: RecipeDetailUiState,
    numbers: RecipeNumbers,
    viewModel: RecipeDetailViewModel,
    onCook: () -> Unit,
    onAddToList: () -> Unit,
    onCooked: () -> Unit,
    onLogPortion: () -> Unit,
    onStartTimer: TimerStarter,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(recipe)
        NumberChips(numbers)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCook, modifier = Modifier.testTag(RecipeDetailTags.COOK)) { Text("Cook") }
            OutlinedButton(onClick = onAddToList, modifier = Modifier.testTag(RecipeDetailTags.ADD_TO_LIST)) {
                Text("Add to shopping list")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCooked, modifier = Modifier.testTag(RecipeDetailTags.COOKED)) { Text("I cooked this") }
            OutlinedButton(onClick = onLogPortion, modifier = Modifier.testTag(RecipeDetailTags.LOG_PORTION)) { Text("Log a portion") }
        }
        OptionalSwitch(checked = state.countOptionalExtras, onChange = viewModel::setCountOptionalExtras)
        IngredientsCard(state = state, onServings = viewModel::setServings)
        MethodCard(recipe.method, onStartTimer)
        TextListCard("Kit", recipe.equipment)
        TextListCard("Tips", recipe.tips)
        TextListCard("Freezing and storing", recipe.storage)
        CostCard(numbers)
        NutritionCard(numbers)
        VitaminsCard(numbers)
        VegCard(numbers.vegSummary, state.ingredients)
        BreakdownCard(numbers, state.ingredients)
        RatingAndNotes(recipe = recipe, onRate = viewModel::setRating, onNotes = viewModel::setNotes)
        Spacer(Modifier.height(24.dp))
    }
}

/** Vitamins and minerals per portion (docs/PLAN.md §14.2), as amounts and % of the daily reference. */
@Composable
private fun VitaminsCard(numbers: RecipeNumbers) {
    val intake = numbers.intake
    SectionCard(title = "Vitamins and minerals", modifier = Modifier.testTag(RecipeDetailTags.VITAMINS)) {
        Text(
            "Per portion, against the daily reference on UK labels. Typical values from the food list or your labels.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Micro.entries.forEach { micro ->
            val amount = intake[micro]
            val unknown = IntakeKey.of(micro) in intake.unknown
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(micro.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    (if (unknown) "at least " else "") + "${micro.format(amount)} · ${Math.round(micro.percent(amount))}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Header(recipe: Recipe) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(recipe.title, style = MaterialTheme.typography.headlineSmall)
        recipe.tagline?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        recipe.style?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val times = listOfNotNull(
            recipe.prepMins?.let { "Prep ${FoodFormat.minutes(it)}" },
            recipe.cookMins?.let { "Cook ${FoodFormat.minutes(it)}" },
            recipe.timeNote,
        )
        if (times.isNotEmpty()) {
            Text(times.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recipe.servedWith?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NumberChips(numbers: RecipeNumbers) {
    val perPortion = numbers.nutrition.perPortion
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberChip(modifier = Modifier.testTag(RecipeDetailTags.COST)) {
            Text(FoodFormat.cost(numbers.cost.perPortion, numbers.cost.atLeast) + " a portion")
        }
        NumberChip(modifier = Modifier.testTag(RecipeDetailTags.KCAL)) { Text(FoodFormat.kcal(perPortion.kcal)) }
        NumberChip { Text("${FoodFormat.grams(perPortion.protein)} protein") }
        NumberChip {
            Text("${FoodFormat.grams(perPortion.salt)} salt")
            LightBadge(numbers.nutrition.lights[Nutrient.SALT] ?: Light.GREEN)
        }
        NumberChip {
            VegDots(numbers.veg)
            Text("${FoodFormat.veg(numbers.veg)} veg")
        }
    }
}

@Composable
private fun OptionalSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .testTag(RecipeDetailTags.OPTIONAL_SWITCH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Count optional extras", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun IngredientsCard(state: RecipeDetailUiState, onServings: (Int) -> Unit) {
    SectionCard(title = "Ingredients") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Servings", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            ServingsStepper(servings = state.servings, onServingsChange = onServings)
        }
        var previousGroup: String? = null
        state.lines.forEachIndexed { index, line ->
            if (line.group != null && line.group != previousGroup) {
                Text(
                    line.group.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            previousGroup = line.group
            Row(modifier = Modifier.padding(vertical = 3.dp).testTag(RecipeDetailTags.line(index))) {
                Text("•", modifier = Modifier.width(16.dp))
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (line.counted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (line.optional) {
                    Spacer(Modifier.width(6.dp))
                    Text("optional", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun MethodCard(steps: List<RecipeStep>, onStartTimer: TimerStarter) {
    if (steps.isEmpty()) return
    SectionCard(title = "Method") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tap a time to start a timer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                CustomTimerButton(onStart = onStartTimer)
            }
            steps.forEachIndexed { index, step -> StepView(index = index, step = step, large = false, onStartTimer = onStartTimer) }
        }
    }
}

/**
 * One method step: number, title, time chip, its timers ([StepTimerChips], when [onStartTimer] is given) and its
 * paragraphs, cues and rescues in coloured boxes.
 */
@Composable
internal fun StepView(index: Int, step: RecipeStep, large: Boolean, done: Boolean = false, onStartTimer: TimerStarter? = null) {
    val bodyStyle = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
    val faded = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.testTag(RecipeDetailTags.step(index)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}. ${step.title ?: ""}".trimEnd(),
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = faded,
                modifier = Modifier.weight(1f, fill = false),
            )
            step.time?.takeIf { it.isNotBlank() }?.let { time ->
                Spacer(Modifier.width(8.dp))
                Text(
                    time,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (onStartTimer != null) StepTimerChips(index = index, step = step, onStart = onStartTimer)
        RecipeSteps.paragraphs(step.body).forEach { paragraph ->
            when (paragraph.kind) {
                ParagraphKind.PLAIN -> Text(paragraph.text, style = bodyStyle, color = faded)
                ParagraphKind.CUE -> Callout(
                    lead = paragraph.lead,
                    rest = paragraph.rest,
                    style = bodyStyle,
                    background = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                ParagraphKind.RESCUE -> Callout(
                    lead = paragraph.lead,
                    rest = paragraph.rest,
                    style = bodyStyle,
                    background = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun Callout(
    lead: String,
    rest: String,
    style: androidx.compose.ui.text.TextStyle,
    background: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$lead:") }
            append(" ")
            append(rest)
        },
        style = style,
        color = content,
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun TextListCard(title: String, items: List<RecipeStep>) {
    if (items.isEmpty()) return
    SectionCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                Row {
                    Text("•", modifier = Modifier.width(16.dp))
                    Text(item.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CostCard(numbers: RecipeNumbers) {
    val cost = numbers.cost
    SectionCard(title = "Cost") {
        CostRow("Per portion", FoodFormat.cost(cost.perPortion, cost.atLeast))
        CostRow("Whole dish (${FoodFormat.count(numbers.servings, "portion")})", FoodFormat.cost(cost.whole, cost.atLeast))
        CostRow("From an empty cupboard", FoodFormat.cost(cost.fromEmptyCupboard, cost.atLeast))
        val notes = buildList {
            if (cost.oldPrice.isNotEmpty()) add(FoodFormat.oldPrices(cost.oldPrice.size))
            if (cost.missingPrice.isNotEmpty()) add(FoodFormat.missingPrices(cost.missingPrice.size))
        }
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CostRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/** The label rows, in the order UK packs use. */
private val LABEL_ROWS = listOf(
    Nutrient.KCAL to "Energy",
    Nutrient.FAT to "Fat",
    Nutrient.SATURATES to "of which saturates",
    Nutrient.CARBS to "Carbohydrate",
    Nutrient.SUGARS to "of which sugars",
    Nutrient.ADDED_SUGARS to "of which added sugars",
    Nutrient.FIBRE to "Fibre",
    Nutrient.PROTEIN to "Protein",
    Nutrient.SALT to "Salt",
)

@Composable
private fun NutritionCard(numbers: RecipeNumbers) {
    var per100 by rememberSaveable { mutableStateOf(false) }
    val nutrition = numbers.nutrition
    SectionCard(title = "Nutrition") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !per100, onClick = { per100 = false }, label = { Text("Per portion") })
            FilterChip(
                selected = per100,
                onClick = { per100 = true },
                label = { Text("Per 100 g") },
                modifier = Modifier.testTag(RecipeDetailTags.PER_100),
            )
        }
        Spacer(Modifier.height(4.dp))
        val values = if (per100) nutrition.per100 else nutrition.perPortion
        LABEL_ROWS.forEach { (nutrient, label) ->
            val value = values[nutrient]
            val amount = if (nutrient == Nutrient.KCAL) {
                "${FoodFormat.kcal(value)} · ${FoodFormat.kj(values.kj)}"
            } else {
                FoodFormat.grams(value)
            }
            val addedUnknown = nutrient == Nutrient.ADDED_SUGARS && nutrition.addedSugarsUnknown.isNotEmpty()
            val percent = if (per100) {
                null
            } else if (nutrient == Nutrient.FIBRE) {
                "${FoodFormat.percent(nutrition.percentRi(nutrient))} of 30 g a day"
            } else if (nutrient == Nutrient.ADDED_SUGARS) {
                "${FoodFormat.percent(nutrition.percentRi(nutrient))} of the 30 g a day limit"
            } else {
                "${FoodFormat.percent(nutrition.percentRi(nutrient))} RI"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .then(if (nutrient == Nutrient.ADDED_SUGARS) Modifier.testTag(RecipeDetailTags.ADDED_SUGARS) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(
                        start = when {
                            nutrient == Nutrient.ADDED_SUGARS -> 24.dp
                            label.startsWith("of which") -> 12.dp
                            else -> 0.dp
                        },
                    ),
                )
                if (nutrient in TrafficLights.coloured) {
                    LightBadge(nutrition.lights[nutrient] ?: Light.GREEN)
                    Spacer(Modifier.width(10.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (addedUnknown) "at least $amount" else amount, style = MaterialTheme.typography.bodyLarge)
                    percent?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(
            if (per100) {
                "Per 100 g of the raw ingredients: cooking changes the weight, so this is a guide."
            } else {
                "RI: reference intake of an average adult (2,000 kcal). Colours as on UK packs."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Added sugars: sugar, honey and syrups put into a food. UK labels don't list them, so set them on an " +
                "ingredient to count them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (nutrition.atLeast) {
            Text(
                FoodFormat.missingNutrition(nutrition.unknownIngredients.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (nutrition.addedSugarsUnknown.isNotEmpty()) {
            Text(
                FoodFormat.missingAddedSugars(nutrition.addedSugarsUnknown.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 5 a day per portion (docs/PLAN.md §11.11): what counts towards it (beans and lentils once at most) and the total,
 * then each ingredient's portions, the pulses together when the cap takes some off.
 */
@Composable
private fun VegCard(veg: VegSummary, ingredients: Map<Long, Ingredient>) {
    SectionCard(title = "5 a day") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(RecipeDetailTags.VEG_EFFECTIVE)) {
            VegDots(veg.effective, dotSize = 14.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "${FoodFormat.veg(veg.effective)} towards your 5 a day",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            "${FoodFormat.veg(veg.total)} portions of veg and pulses in total",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp).testTag(RecipeDetailTags.VEG_TOTAL),
        )
        Text(
            "Per portion of the recipe; one portion of 5 a day is 80 g.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (veg.items.isNotEmpty()) Spacer(Modifier.height(6.dp))
        val pulses = veg.items.filter { it.kind == VegKind.PULSE }
        val rows = if (veg.capped) veg.items.filter { it.kind != VegKind.PULSE } else veg.items
        rows.forEach { item ->
            VegRow(ingredients[item.ingredientId]?.name ?: "Ingredient", FoodFormat.veg(item.portions))
        }
        if (veg.capped) {
            val names = pulses.mapNotNull { ingredients[it.ingredientId]?.name }.joinToString(", ")
            VegRow(
                "Beans and lentils ($names)",
                "${FoodFormat.veg(veg.pulses)}, counted as ${FoodFormat.veg(veg.pulsesCounted)}",
            )
        }
        if (pulses.isNotEmpty()) {
            Text(
                "Beans and lentils count as one portion at most, however much there is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun VegRow(name: String, portions: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(portions, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun metricLabel(metric: BreakdownMetric): String = when (metric) {
    BreakdownMetric.COST -> "Cost"
    BreakdownMetric.KCAL -> "kcal"
    BreakdownMetric.SALT -> "Salt"
    BreakdownMetric.PROTEIN -> "Protein"
}

/** Where a number comes from: the five ingredients that give most of it, and everything else. */
@Composable
private fun BreakdownCard(numbers: RecipeNumbers, ingredients: Map<Long, Ingredient>) {
    var metric by rememberSaveable { mutableStateOf(BreakdownMetric.COST) }
    val breakdown = numbers.breakdowns[metric] ?: return
    SectionCard(title = "Where it comes from") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BreakdownMetric.entries.forEach { option ->
                FilterChip(selected = option == metric, onClick = { metric = option }, label = { Text(metricLabel(option)) })
            }
        }
        Spacer(Modifier.height(6.dp))
        val shares = breakdown.top + listOfNotNull(breakdown.rest)
        if (shares.isEmpty()) {
            Text("Nothing to show yet.", style = MaterialTheme.typography.bodyMedium)
        }
        shares.forEach { share ->
            val name = share.ingredientId?.let { ingredients[it]?.name } ?: "Everything else"
            val value = when (metric) {
                BreakdownMetric.COST -> Money(Math.round(share.value)).format()
                BreakdownMetric.KCAL -> FoodFormat.kcal(share.value)
                BreakdownMetric.SALT, BreakdownMetric.PROTEIN -> FoodFormat.grams(share.value)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("$value · ${FoodFormat.share(share.fraction)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RatingAndNotes(recipe: Recipe, onRate: (Int) -> Unit, onNotes: (String) -> Unit) {
    var notes by rememberSaveable(recipe.id) { mutableStateOf(recipe.notes) }
    // Saved a moment after typing stops, so every keystroke isn't a write.
    LaunchedEffect(notes) {
        if (notes != recipe.notes) {
            delay(600)
            onNotes(notes)
        }
    }
    SectionCard(title = "Your notes") {
        RatingPicker(rating = recipe.rating, onRate = onRate)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes (what to change next time)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag(RecipeDetailTags.NOTES),
        )
    }
}

@Composable
private fun AddToListDialog(recipe: Recipe, onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    var servings by remember { mutableStateOf(recipe.serves) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to the shopping list") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Servings", modifier = Modifier.weight(1f))
                ServingsStepper(servings = servings, onServingsChange = { servings = it })
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(servings) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
