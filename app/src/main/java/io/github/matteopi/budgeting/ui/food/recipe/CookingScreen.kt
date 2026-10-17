@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.matteopi.budgeting.ui.food.recipe

import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.food.components.ServingsStepper
import io.github.matteopi.budgeting.ui.food.components.findHostActivity
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Test tags of the cooking view. */
object CookingTags {
    const val DONE = "cooking-done"

    fun line(index: Int) = "cooking-line-$index"

    fun step(index: Int) = "cooking-step-$index"

    /** The names ticks are stored under (`SettingsStore.observeCookingTicks`). */
    fun lineTick(index: Int) = "line-$index"

    fun stepTick(index: Int) = "step-$index"
}

data class CookingUiState(
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    val servings: Int = 1,
    val lines: List<DetailLine> = emptyList(),
    val ticks: Set<String> = emptySet(),
)

/** The cooking view's state: the recipe scaled to its own servings stepper, and what is ticked off. */
class CookingViewModel(private val container: AppContainer, private val recipeId: Long) : ViewModel() {
    private val servingsFlow = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<CookingUiState> = combine(
        container.observeFoodBook(),
        servingsFlow,
        container.settingsStore.observeCookingTicks(recipeId),
    ) { book, chosen, ticks ->
        val recipe = book.recipes.firstOrNull { it.id == recipeId }
        if (recipe == null) {
            CookingUiState(loading = false)
        } else {
            val servings = chosen ?: recipe.serves
            CookingUiState(
                loading = false,
                recipe = recipe,
                servings = servings,
                lines = RecipeDetailMapping.lines(recipe, book.ingredients, servings),
                ticks = ticks,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CookingUiState())

    fun setServings(servings: Int) {
        servingsFlow.value = servings.coerceIn(1, 24)
    }

    fun toggle(tick: String) {
        val ticked = tick !in uiState.value.ticks
        container.appScope.launch { container.settingsStore.setCookingTick(recipeId, tick, ticked) }
    }

    /** *Done cooking*: forgets the ticks. */
    fun done(onDone: () -> Unit) {
        viewModelScope.launch {
            container.settingsStore.clearCookingTicks(recipeId)
            onDone()
        }
    }

    companion object {
        fun factory(recipeId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                CookingViewModel(app.container, recipeId)
            }
        }
    }
}

/**
 * The cooking view (docs/PLAN.md §11.8): the recipe in large text with the screen kept on while it is open, its own
 * servings stepper, and every ingredient and step ticked off with a tap. Ticks are kept until *Done cooking*.
 */
@Composable
fun CookingScreen(
    recipeId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CookingViewModel = viewModel(key = "cooking-$recipeId", factory = CookingViewModel.factory(recipeId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The screen stays on while the cooking view is open, and only then.
    DisposableEffect(Unit) {
        val window = context.findHostActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.recipe?.let { it.shortTitle ?: it.title } ?: "Cooking",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { viewModel.done(onBack) }) { Text("Done cooking") }
                },
            )
        },
    ) { innerPadding ->
        val recipe = state.recipe
        when {
            state.loading -> Unit
            recipe == null -> EmptyState("This recipe is no longer there.", modifier = Modifier.padding(innerPadding))
            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "servings") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Servings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        ServingsStepper(servings = state.servings, onServingsChange = viewModel::setServings)
                    }
                }
                item(key = "ingredients-heading") {
                    Text("Ingredients", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
                }
                itemsIndexed(state.lines, key = { index, _ -> "line-$index" }) { index, line ->
                    val tick = CookingTags.lineTick(index)
                    TickRow(
                        ticked = tick in state.ticks,
                        onToggle = { viewModel.toggle(tick) },
                        modifier = Modifier.testTag(CookingTags.line(index)),
                    ) { done ->
                        Text(
                            line.text,
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                item(key = "method-heading") {
                    Text("Method", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                }
                itemsIndexed(recipe.method, key = { index, _ -> "step-$index" }) { index, step ->
                    val tick = CookingTags.stepTick(index)
                    TickRow(
                        ticked = tick in state.ticks,
                        onToggle = { viewModel.toggle(tick) },
                        modifier = Modifier.testTag(CookingTags.step(index)),
                    ) { done -> StepView(index = index, step = step, large = true, done = done) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item(key = "done") {
                    Button(
                        onClick = { viewModel.done(onBack) },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(CookingTags.DONE),
                    ) { Text("Done cooking") }
                }
            }
        }
    }
}

/** A row that ticks on a tap anywhere on it, with its checkbox at the start. */
@Composable
private fun TickRow(
    ticked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (done: Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = ticked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) { content(ticked) }
    }
}
