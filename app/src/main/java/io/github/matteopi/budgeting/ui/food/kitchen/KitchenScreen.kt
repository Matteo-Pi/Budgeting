@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food.kitchen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.BatchStatus
import io.github.matteopi.budgeting.core.nutrition.Batches
import io.github.matteopi.budgeting.core.nutrition.FoodPrefs
import io.github.matteopi.budgeting.core.nutrition.Place
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import io.github.matteopi.budgeting.ui.food.diary.BatchSheet
import io.github.matteopi.budgeting.ui.food.diary.DiaryMessage
import io.github.matteopi.budgeting.ui.food.diary.DiaryTags
import io.github.matteopi.budgeting.ui.food.diary.HalfStepper
import io.github.matteopi.budgeting.ui.food.diary.countText
import io.github.matteopi.budgeting.ui.food.diary.gramsText
import io.github.matteopi.budgeting.ui.food.diary.kcalText
import io.github.matteopi.budgeting.ui.food.diary.penceText
import io.github.matteopi.budgeting.ui.food.diary.shortDay
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One place a batch has portions in. */
data class KitchenEntry(val batch: Batch, val place: Place, val status: BatchStatus)

data class KitchenUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val fridge: List<KitchenEntry> = emptyList(),
    val freezer: List<KitchenEntry> = emptyList(),
    val finished: List<Batch> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val portionsIn: Double = 0.0,
    val wastedThisMonthPence: Double = 0.0,
    val prefs: FoodPrefs = FoodPrefs(),
)

/** The Kitchen tab (docs/PLAN.md §14.5): the batches in the fridge and the freezer, and *I cooked*. */
class KitchenViewModel(private val container: AppContainer) : ViewModel() {
    private val _message = MutableStateFlow<DiaryMessage?>(null)
    val message: StateFlow<DiaryMessage?> = _message.asStateFlow()

    val uiState: StateFlow<KitchenUiState> = combine(
        container.diaryRepository.observeBatches(),
        container.observeFoodBook(),
        container.settingsStore.foodPrefs,
        container.clock.todayFlow,
    ) { batches, book, prefs, today ->
        val ready = Batches.ready(batches)
        KitchenUiState(
            loading = false,
            today = today,
            fridge = ready.filter { it.fridgeLeft > Batches.EPSILON }.map { KitchenEntry(it, Place.FRIDGE, Batches.status(it, today, prefs)) },
            freezer = ready.filter { it.freezerLeft > Batches.EPSILON }
                .sortedBy { it.frozenOn ?: it.madeOn }
                .map { KitchenEntry(it, Place.FREEZER, Batches.status(it.copy(fridgeLeft = 0.0), today, prefs)) },
            finished = Batches.recentlyFinished(batches, today),
            recipes = book.liveRecipes,
            portionsIn = ready.sumOf { it.left },
            wastedThisMonthPence = Batches.wastedPence(batches, today.withDayOfMonth(1), today),
            prefs = prefs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KitchenUiState())

    fun ateOne(entry: KitchenEntry) {
        viewModelScope.launch {
            val done = container.foodDiary.ateOne(entry.batch.id, entry.place)
            _message.value = if (done == null) DiaryMessage("None left") else DiaryMessage(done.message) {
                viewModelScope.launch { container.diaryRepository.undoEat(done.eaten) }
            }
        }
    }

    fun move(entry: KitchenEntry, amount: Double) {
        val to = if (entry.place == Place.FRIDGE) Place.FREEZER else Place.FRIDGE
        viewModelScope.launch {
            container.diaryRepository.move(entry.batch.id, amount, to)
            _message.value = DiaryMessage(if (to == Place.FREEZER) "Moved to the freezer" else "Moved to the fridge: eat by tomorrow")
        }
    }

    fun throwAway(entry: KitchenEntry, amount: Double) {
        viewModelScope.launch {
            val taken = container.diaryRepository.throwAway(entry.batch.id, amount, entry.place) ?: return@launch
            _message.value = DiaryMessage("Threw away ${countText(taken.total)} of ${entry.batch.name}") {
                viewModelScope.launch { container.diaryRepository.undoThrowAway(entry.batch.id, taken) }
            }
        }
    }

    fun saveEdit(batch: Batch, fridge: Double, freezer: Double, eatBy: LocalDate?) {
        viewModelScope.launch {
            container.diaryRepository.updateBatch(
                batch.copy(
                    fridgeLeft = fridge,
                    freezerLeft = freezer,
                    eatBy = eatBy,
                    frozenOn = if (freezer > Batches.EPSILON) batch.frozenOn ?: container.clock.today() else null,
                ),
            )
        }
    }

    fun delete(batch: Batch) {
        viewModelScope.launch {
            container.diaryRepository.deleteBatch(batch.id)
            _message.value = DiaryMessage("Deleted ${batch.name} from the kitchen")
        }
    }

    fun cook(recipe: Recipe, servings: Double, portions: Double, freezer: Double, madeOn: LocalDate) {
        viewModelScope.launch {
            container.foodDiary.cook(recipe, servings, portions, freezer, madeOn)
            _message.value = DiaryMessage("${recipe.shortTitle ?: recipe.title} is in the kitchen")
        }
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                KitchenViewModel(app.container)
            }
        }
    }
}

/** What a batch's line under its name says about its dates. */
fun datesText(entry: KitchenEntry, prefs: FoodPrefs): String {
    val batch = entry.batch
    val made = "Made ${shortDay(batch.madeOn)}"
    return if (entry.place == Place.FRIDGE) {
        val eatBy = batch.eatBy?.let { " · eat by ${shortDay(it)}" } ?: ""
        "$made$eatBy"
    } else {
        val bestBy = Batches.freezerBestBy(batch, prefs)?.let { " · best by ${shortDay(it)}" } ?: ""
        "Frozen ${shortDay(batch.frozenOn ?: batch.madeOn)}$bestBy"
    }
}

@Composable
fun KitchenScreen(
    onOpenRecipe: (Long) -> Unit,
    onLogFood: (batchId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KitchenViewModel = viewModel(factory = KitchenViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var choosingRecipe by remember { mutableStateOf(false) }
    var cooking by remember { mutableStateOf<Recipe?>(null) }
    var moving by remember { mutableStateOf<KitchenEntry?>(null) }
    var throwing by remember { mutableStateOf<KitchenEntry?>(null) }
    var editing by remember { mutableStateOf<Batch?>(null) }

    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        viewModel.messageShown()
        val result = snackbarHostState.showSnackbar(shown.text, actionLabel = if (shown.undo != null) "Undo" else null, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) shown.undo?.invoke()
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Kitchen") }, navigationIcon = { SectionSwitchButton(AppSection.RECIPES) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { choosingRecipe = true },
                icon = { Icon(Icons.Default.Add, contentDescription = "I cooked") },
                text = { Text("I cooked") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "totals") {
                Text(
                    "${countText(state.portionsIn)} portions in · ${penceText(state.wastedThisMonthPence)} thrown away this month",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.loading && state.fridge.isEmpty() && state.freezer.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing cooked yet. Tap I cooked after making a recipe: say how many portions it made and how many " +
                            "went in the freezer, then tap Ate one when you eat one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.fridge.isNotEmpty()) item(key = "fridge") { Text("Fridge", style = MaterialTheme.typography.titleMedium) }
            items(state.fridge, key = { "fridge-${it.batch.id}" }) { entry ->
                BatchCard(entry, state.prefs, onAteOne = { viewModel.ateOne(entry) }, onMove = { moving = entry }, onThrow = { throwing = entry },
                    onEdit = { editing = entry.batch }, onOpen = { onLogFood(entry.batch.id) })
            }
            if (state.freezer.isNotEmpty()) item(key = "freezer") { Text("Freezer", style = MaterialTheme.typography.titleMedium) }
            items(state.freezer, key = { "freezer-${it.batch.id}" }) { entry ->
                BatchCard(entry, state.prefs, onAteOne = { viewModel.ateOne(entry) }, onMove = { moving = entry }, onThrow = { throwing = entry },
                    onEdit = { editing = entry.batch }, onOpen = { onLogFood(entry.batch.id) })
            }
            if (state.finished.isNotEmpty()) {
                item(key = "finished") { Text("Finished", style = MaterialTheme.typography.titleMedium) }
                items(state.finished, key = { "done-${it.id}" }) { batch ->
                    Text(
                        "${batch.name} · made ${shortDay(batch.madeOn)}" + if (batch.wasted > 0) " · ${countText(batch.wasted)} thrown away" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { batch.recipeId?.let(onOpenRecipe) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (choosingRecipe) {
        AlertDialog(
            onDismissRequest = { choosingRecipe = false },
            title = { Text("What did you cook?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (state.recipes.isEmpty()) Text("Add a recipe first on the Recipes tab.")
                    state.recipes.forEach { recipe ->
                        Text(
                            recipe.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { choosingRecipe = false; cooking = recipe }.padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingRecipe = false }) { Text("Cancel") } },
        )
    }
    cooking?.let { recipe ->
        BatchSheet(recipe, state.today, onDismiss = { cooking = null }) { servings, portions, freezer, madeOn ->
            viewModel.cook(recipe, servings, portions, freezer, madeOn)
            cooking = null
        }
    }
    moving?.let { entry ->
        AmountDialog(
            title = if (entry.place == Place.FRIDGE) "Move to the freezer" else "Move to the fridge",
            max = if (entry.place == Place.FRIDGE) entry.batch.fridgeLeft else entry.batch.freezerLeft,
            confirm = "Move",
            onDismiss = { moving = null },
        ) { amount ->
            viewModel.move(entry, amount)
            moving = null
        }
    }
    throwing?.let { entry ->
        AmountDialog(
            title = "Throw away",
            max = if (entry.place == Place.FRIDGE) entry.batch.fridgeLeft else entry.batch.freezerLeft,
            confirm = "Throw away",
            onDismiss = { throwing = null },
        ) { amount ->
            viewModel.throwAway(entry, amount)
            throwing = null
        }
    }
    editing?.let { batch ->
        EditBatchDialog(batch, onDismiss = { editing = null }, onDelete = { viewModel.delete(batch); editing = null }) { fridge, freezer ->
            viewModel.saveEdit(batch, fridge, freezer, batch.eatBy)
            editing = null
        }
    }
}

@Composable
private fun BatchCard(
    entry: KitchenEntry,
    prefs: FoodPrefs,
    onAteOne: () -> Unit,
    onMove: () -> Unit,
    onThrow: () -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
) {
    val batch = entry.batch
    val left = if (entry.place == Place.FRIDGE) batch.fridgeLeft else batch.freezerLeft
    val warn = entry.status == BatchStatus.PAST_EAT_BY || entry.status == BatchStatus.EAT_TODAY || entry.status == BatchStatus.FROZEN_LONG
    Card(Modifier.fillMaxWidth().testTag(if (entry.place == Place.FRIDGE) DiaryTags.batch(batch.id) else DiaryTags.frozen(batch.id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(batch.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(onClick = onOpen))
            Text(
                "${countText(left)} ${if (left > 1 + 1e-9) "portions" else "portion"} · ${datesText(entry, prefs)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (warn || entry.status == BatchStatus.EAT_TOMORROW) {
                Text(
                    entry.status.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "${kcalText(batch.intake.kcal)} · ${gramsText(batch.intake.protein)} protein · ${penceText(batch.costPence)} a portion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAteOne) { Text("Ate one") }
                TextButton(onClick = onMove) { Text(if (entry.place == Place.FRIDGE) "Freeze" else "Defrost") }
                TextButton(onClick = onThrow) { Text("Throw away") }
                TextButton(onClick = onEdit) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun AmountDialog(title: String, max: Double, confirm: String, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var amount by remember { mutableStateOf(minOf(1.0, max).coerceAtLeast(0.5)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { HalfStepper(amount, { amount = it }, "portions", max = max.coerceAtLeast(0.5)) },
        confirmButton = { TextButton(onClick = { onConfirm(amount) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditBatchDialog(batch: Batch, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Double, Double) -> Unit) {
    var fridge by remember(batch.id) { mutableStateOf(batch.fridgeLeft) }
    var freezer by remember(batch.id) { mutableStateOf(batch.freezerLeft) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(batch.name) },
        text = {
            Column {
                Text("In the fridge")
                HalfStepper(fridge, { fridge = it }, "in the fridge", min = 0.0)
                Text("In the freezer")
                HalfStepper(freezer, { freezer = it }, "in the freezer", min = 0.0)
                TextButton(onClick = onDelete) { Text("Delete batch", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(fridge, freezer) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
