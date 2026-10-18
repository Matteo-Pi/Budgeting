package io.github.matteopi.budgeting.ui.food

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.AppIcons
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientRowUi
import io.github.matteopi.budgeting.ui.food.ingredient.OwnedDialog
import io.github.matteopi.budgeting.ui.food.ingredient.OwnedFilter
import io.github.matteopi.budgeting.ui.food.scan.PackPhotoButton
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton
import kotlinx.coroutines.launch

/** Test tags of the Ingredients tab. */
object IngredientsTags {
    const val SEARCH = "ingredients-search"
    const val SCAN = "ingredients-scan"

    fun row(ingredientId: Long) = "ingredients-row-$ingredientId"

    /** The *How much I have* button of a row. */
    fun owned(ingredientId: Long) = "ingredients-owned-$ingredientId"

    fun filter(filter: OwnedFilter) = "ingredients-filter-${filter.name.lowercase()}"
}

/**
 * The Ingredients tab (docs/PLAN.md §11.8): the ingredients grouped by shelf in shop order, with a search.
 * Each row shows the price per pack with where it came from and when ("£0.99 · 1 kg bag · typed 15 Oct", or
 * "paid £0.89 at Tesco 3 Nov" from a receipt), an *old* tag for a price over 120 days old, and hints for
 * "no price" and "no nutrition". The button adds an ingredient; a row opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientsScreen(
    onAddIngredient: () -> Unit = {},
    onOpenIngredient: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    onNewWithBarcode: (String) -> Unit = {},
    viewModel: IngredientsViewModel = viewModel(factory = IngredientsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { viewModel.setQuery(query) }
    var ownedFor by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Ingredients") },
                navigationIcon = { SectionSwitchButton(AppSection.RECIPES) },
                actions = {
                    // A known barcode opens its ingredient; an unknown one starts a new ingredient with it filled in.
                    PackPhotoButton(
                        label = "Scan barcode",
                        tag = IngredientsTags.SCAN,
                        enabled = true,
                        onPhoto = { uri ->
                            viewModel.scanBarcode(
                                context = context,
                                uri = uri,
                                onKnown = onOpenIngredient,
                                onNew = onNewWithBarcode,
                                onNone = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                            )
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddIngredient) {
                Icon(Icons.Default.Add, contentDescription = "Add ingredient")
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search ingredients") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(IngredientsTags.SEARCH),
            )
            if (state.hasAny) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    OwnedFilter.entries.forEach { option ->
                        FilterChip(
                            selected = state.filter == option,
                            onClick = { viewModel.setFilter(option) },
                            label = { Text(if (option == OwnedFilter.OWNED) "${option.label} (${state.ownedCount})" else option.label) },
                            modifier = Modifier.testTag(IngredientsTags.filter(option)),
                        )
                    }
                }
            }
            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
                !state.hasAny -> EmptyState(
                    "No ingredients yet — tap + to add one, or add the starter recipes from the Recipes tab.",
                )
                state.sections.isEmpty() && state.filter == OwnedFilter.OWNED && query.isBlank() -> EmptyState(
                    "Nothing here yet. Tap the fridge on an ingredient to say how much of it you have.",
                )
                state.sections.isEmpty() -> EmptyState(
                    if (query.isBlank()) "No ingredients here." else "No ingredients match \"${query.trim()}\".",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    state.sections.forEach { section ->
                        item(key = "shelf-${section.shelf.name}") {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(section.rows, key = { it.id }) { row ->
                            IngredientRow(row = row, onClick = { onOpenIngredient(row.id) }, onOwned = { ownedFor = row.id })
                        }
                    }
                }
            }
        }
    }

    val ownedId = ownedFor
    val ownedIngredient = ownedId?.let { state.ingredients[it] }
    if (ownedId != null && ownedIngredient != null) {
        OwnedDialog(
            ingredient = ownedIngredient,
            current = state.owned[ownedId],
            onSave = { quantity ->
                viewModel.setOwned(ownedId, quantity)
                ownedFor = null
            },
            onDismiss = { ownedFor = null },
        )
    }
}

@Composable
private fun IngredientRow(row: IngredientRowUi, onClick: () -> Unit, onOwned: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IngredientRowText(row = row, onClick = onClick, modifier = Modifier.weight(1f))
        IconButton(onClick = onOwned, modifier = Modifier.testTag(IngredientsTags.owned(row.id))) {
            Icon(
                AppIcons.Kitchen,
                contentDescription = "How much ${row.name} I have",
                tint = if (row.ownedText != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IngredientRowText(row: IngredientRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag(IngredientsTags.row(row.id)),
    ) {
        Text(row.name, style = MaterialTheme.typography.bodyLarge)
        if (row.ownedText != null) {
            Text(
                text = row.ownedText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (row.priceLine != null) {
            Text(
                text = row.priceLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.old || row.noPrice || row.noNutrition) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                if (row.old) Hint("old")
                if (row.noPrice) Hint("no price")
                if (row.noNutrition) Hint("no nutrition")
            }
        }
    }
}

/** A small tag such as "old" or "no price": text as well as colour. */
@Composable
private fun Hint(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
