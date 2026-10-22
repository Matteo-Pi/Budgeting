package io.github.matteopi.budgeting.ui.food.foods

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.components.EmptyState

/** Test tags of the food list. */
object FoodListTags {
    const val SEARCH = "foods-search"

    fun row(key: String) = "foods-row-$key"
}

/**
 * The food list (docs/PLAN.md §11.12): the built-in foods with their Tesco pack price and energy per 100 g, by shelf
 * or as searched. A tap adds the food as one of the user's ingredients and opens it in the editor, or opens the
 * ingredient they already have with that name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodListScreen(
    onBack: () -> Unit,
    onOpenIngredient: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodListViewModel = viewModel(factory = FoodListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { viewModel.setQuery(query) }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Food list") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search foods") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(FoodListTags.SEARCH),
            )
            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
                state.sections.isEmpty() -> EmptyState(
                    "No food on the list matches \"${query.trim()}\". You can still add it yourself from the Ingredients tab.",
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (query.isBlank()) {
                        item(key = "about") {
                            Text(
                                state.about,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    state.sections.forEach { section ->
                        section.title?.let { title ->
                            item(key = "shelf-${section.shelf?.name}") {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                        }
                        items(section.rows, key = { it.key }) { row ->
                            FoodRow(
                                row = row,
                                onClick = {
                                    val existing = row.existingId
                                    if (existing != null) onOpenIngredient(existing) else viewModel.add(row.key, onOpenIngredient)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodRow(row: FoodRowUi, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(FoodListTags.row(row.key)),
    ) {
        Text(row.name, style = MaterialTheme.typography.bodyLarge)
        Text(row.priceLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(row.nutritionLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (row.existingId != null) {
            Text("Already in your ingredients", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
