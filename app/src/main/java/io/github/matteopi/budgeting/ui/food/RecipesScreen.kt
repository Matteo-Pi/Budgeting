package io.github.matteopi.budgeting.ui.food

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.food.components.BundleImportDialogs
import io.github.matteopi.budgeting.ui.food.components.FoodFormat
import io.github.matteopi.budgeting.ui.food.components.LightDot
import io.github.matteopi.budgeting.ui.food.components.RatingStars
import io.github.matteopi.budgeting.ui.food.components.VegDots
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton

/** Test tags of the Recipes tab. */
object RecipesTags {
    const val SEARCH = "recipes-search"
    const val EMPTY = "recipes-empty"
    const val SORT = "recipes-sort"

    fun card(recipeId: Long) = "recipe-card-$recipeId"
}

/**
 * The Recipes tab, the Recipes section's first screen (docs/PLAN.md §11.8): search, tag chips and a sort menu
 * above a card per recipe (cost per portion, kcal, protein, salt with its colour, veg, time and rating at the
 * recipe's own servings). A tap opens the recipe; the button adds one. With no recipes it offers the starter
 * recipes and pasting one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    onOpenSettings: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onAddRecipe: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipesViewModel = viewModel(factory = RecipesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by viewModel.importer.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(importState.message) {
        val message = importState.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.importer.messageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                navigationIcon = { SectionSwitchButton(AppSection.RECIPES) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Recipe settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddRecipe,
                // Material clears the extended button's text from accessibility, so the icon carries its name.
                icon = { Icon(Icons.Default.Add, contentDescription = "Add recipe") },
                text = { Text("Add recipe") },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                state.loading -> Unit
                state.totalCount == 0 -> NoRecipesYet(
                    onAddStarter = viewModel::addStarterRecipes,
                    onPaste = onAddRecipe,
                )
                else -> RecipeList(
                    state = state,
                    onQuery = viewModel::setQuery,
                    onToggleTag = viewModel::toggleTag,
                    onSort = viewModel::setSort,
                    onClearFilters = viewModel::clearFilters,
                    onOpenRecipe = onOpenRecipe,
                )
            }
            if (importState.working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    BundleImportDialogs(state = importState, importer = viewModel.importer)
}

@Composable
private fun NoRecipesYet(onAddStarter: () -> Unit, onPaste: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag(RecipesTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No recipes yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add the four starter recipes, or paste one of your own.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddStarter) { Text("Add the starter recipes") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPaste) { Text("Paste a recipe") }
    }
}

@Composable
private fun RecipeList(
    state: RecipesUiState,
    onQuery: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onSort: (RecipeSort) -> Unit,
    onClearFilters: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "controls") {
            RecipeControls(
                query = query,
                onQuery = {
                    query = it
                    onQuery(it)
                },
                tags = state.tags,
                sort = state.sort,
                onToggleTag = onToggleTag,
                onSort = onSort,
            )
        }
        if (state.cards.isEmpty()) {
            item(key = "no-match") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No recipes match", style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = {
                            query = ""
                            onClearFilters()
                        },
                    ) { Text("Clear search and tags") }
                }
            }
        }
        items(state.cards, key = { it.id }) { card ->
            RecipeCardView(card = card, onClick = { onOpenRecipe(card.id) })
        }
    }
}

@Composable
private fun RecipeControls(
    query: String,
    onQuery: (String) -> Unit,
    tags: List<TagChip>,
    sort: RecipeSort,
    onToggleTag: (String) -> Unit,
    onSort: (RecipeSort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search recipes") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag(RecipesTags.SEARCH),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "sort") { SortChip(sort = sort, onSort = onSort) }
            items(tags, key = { "tag-" + it.name }) { tag ->
                FilterChip(
                    selected = tag.selected,
                    onClick = { onToggleTag(tag.name) },
                    label = { Text(tag.name) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChip(sort: RecipeSort, onSort: (RecipeSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("Sort: ${sort.label}") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.testTag(RecipesTags.SORT),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RecipeSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSort(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecipeCardView(card: RecipeCard, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(RecipesTags.card(card.id))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    card.style?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (card.tags.isNotEmpty()) {
                        Text(
                            card.tags.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = FoodFormat.cost(card.cost, card.costAtLeast),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "a portion",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(FoodFormat.kcal(card.kcal), style = MaterialTheme.typography.bodyMedium)
                Text("${FoodFormat.grams(card.protein)} protein", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LightDot(card.saltLight)
                    Spacer(Modifier.width(4.dp))
                    Text("${FoodFormat.grams(card.salt)} salt", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(4.dp))
                    Text(FoodFormat.lightLabel(card.saltLight), style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VegDots(card.veg)
                    Spacer(Modifier.width(6.dp))
                    Text("${FoodFormat.veg(card.veg)} veg", style = MaterialTheme.typography.bodyMedium)
                }
                card.totalMins?.let { Text(FoodFormat.minutes(it), style = MaterialTheme.typography.bodyMedium) }
            }
            RatingStars(card.rating)
        }
    }
}
