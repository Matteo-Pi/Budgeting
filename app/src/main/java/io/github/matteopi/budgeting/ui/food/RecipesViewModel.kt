package io.github.matteopi.budgeting.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.ui.food.components.BundleImporter
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** One tag chip of the Recipes tab. */
data class TagChip(val name: String, val selected: Boolean)

data class RecipesUiState(
    val loading: Boolean = true,
    /** How many recipes there are (not archived), before any search or tag filter. */
    val totalCount: Int = 0,
    /** The cards to show: searched, filtered by tag and sorted. */
    val cards: List<RecipeCard> = emptyList(),
    val query: String = "",
    val tags: List<TagChip> = emptyList(),
    val sort: RecipeSort = RecipeSort.NEWEST,
) {
    /** True while a search or a tag is narrowing the list. */
    val filtering: Boolean get() = query.isNotBlank() || tags.any { it.selected }
}

/**
 * The Recipes tab (docs/PLAN.md §11.8): a card per recipe worked out at its own servings with the real prices
 * ([observeFoodBook]), searched, filtered by tag and sorted; and adding the starter book through [importer].
 */
class RecipesViewModel(container: AppContainer) : ViewModel() {
    private val queryFlow = MutableStateFlow("")
    private val tagsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val sortFlow = MutableStateFlow(RecipeSort.NEWEST)

    /** Adding the starter recipes: its merge question and the snackbar line after it. */
    val importer = BundleImporter(container.recipeRepository, container.appScope)

    val uiState: StateFlow<RecipesUiState> = combine(
        container.observeFoodBook().map { RecipeListMapping.cards(it) },
        queryFlow,
        tagsFlow,
        sortFlow,
    ) { all, query, selected, sort ->
        val available = RecipeListMapping.allTags(all)
        val active = available.filter { it in selected }.toSet()
        RecipesUiState(
            loading = false,
            totalCount = all.size,
            cards = RecipeListMapping.sorted(RecipeListMapping.filter(all, query, active), sort),
            query = query,
            tags = available.map { TagChip(it, it in active) },
            sort = sort,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun setQuery(query: String) {
        queryFlow.value = query
    }

    fun toggleTag(tag: String) {
        tagsFlow.update { if (tag in it) it - tag else it + tag }
    }

    fun setSort(sort: RecipeSort) {
        sortFlow.value = sort
    }

    fun clearFilters() {
        queryFlow.value = ""
        tagsFlow.value = emptySet()
    }

    /** *Add the starter recipes*: asks which ingredients to reuse if the user already has some of them, then adds the book. */
    fun addStarterRecipes() {
        importer.offerStarter()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                RecipesViewModel(app.container)
            }
        }
    }
}
