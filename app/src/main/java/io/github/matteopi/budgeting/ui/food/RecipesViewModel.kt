package io.github.matteopi.budgeting.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.CookMethod
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.FoodGroupKind
import io.github.matteopi.budgeting.ui.food.components.BundleImporter
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** One tag of the *Tags* menu. */
data class TagChip(val name: String, val selected: Boolean)

data class RecipesUiState(
    val loading: Boolean = true,
    /** How many recipes there are (not archived), before any search or filter. */
    val totalCount: Int = 0,
    /** The cards to show: searched, filtered and sorted. */
    val cards: List<RecipeCard> = emptyList(),
    val query: String = "",
    val tags: List<TagChip> = emptyList(),
    val sort: RecipeSort = RecipeSort.NEWEST,
    val filters: RecipeFilters = RecipeFilters(),
    /** What the menus offer, from every recipe, with how many have each (docs/PLAN.md §11.11). */
    val proteinChoices: List<Pair<FoodGroup, Int>> = emptyList(),
    val meatFreeCount: Int = 0,
    val carbChoices: List<Pair<FoodGroup, Int>> = emptyList(),
    val methodChoices: List<Pair<CookMethod, Int>> = emptyList(),
    /** The *Time* menu's limits that some recipe fits, with how many do. */
    val timeChoices: List<Pair<Int, Int>> = emptyList(),
    /** How many recipes each *What I have* choice keeps. */
    val haveCounts: Map<HaveFilter, Int> = emptyMap(),
    /** The kitchen has something recorded, so the cards say how much of each recipe it has. */
    val hasOwned: Boolean = false,
) {
    /** True while a search or a filter is narrowing the list. */
    val filtering: Boolean get() = query.isNotBlank() || filters.active
}

/**
 * The Recipes tab (docs/PLAN.md §11.8, §11.11): a card per recipe worked out at its own servings with the real prices
 * ([observeFoodBook]), searched, filtered (protein, carb, method, time, what I have, tags) and sorted; and adding the
 * starter book through [importer].
 */
class RecipesViewModel(container: AppContainer) : ViewModel() {
    private val queryFlow = MutableStateFlow("")
    private val filtersFlow = MutableStateFlow(RecipeFilters())
    private val sortFlow = MutableStateFlow(RecipeSort.NEWEST)

    /** Adding the starter recipes: its merge question and the snackbar line after it. */
    val importer = BundleImporter(container.recipeRepository, container.appScope)

    val uiState: StateFlow<RecipesUiState> = combine(
        container.observeFoodBook().map { book -> RecipeListMapping.cards(book) to book.owned.isNotEmpty() },
        queryFlow,
        filtersFlow,
        sortFlow,
    ) { (all, hasOwned), query, chosen, sort ->
        val available = RecipeListMapping.allTags(all)
        // A tag that no recipe has any more stops filtering.
        val filters = chosen.copy(tags = available.filter { it in chosen.tags }.toSet())
        RecipesUiState(
            loading = false,
            totalCount = all.size,
            cards = RecipeListMapping.sorted(RecipeListMapping.filter(all, query, filters), sort),
            query = query,
            tags = available.map { TagChip(it, it in filters.tags) },
            sort = sort,
            filters = filters,
            proteinChoices = RecipeListMapping.groupChoices(all, FoodGroupKind.PROTEIN),
            meatFreeCount = all.count { it.meatFree },
            carbChoices = RecipeListMapping.groupChoices(all, FoodGroupKind.CARB),
            methodChoices = RecipeListMapping.methodChoices(all),
            timeChoices = RecipeFilters.TIME_CHOICES.mapNotNull { mins ->
                all.count { card -> card.totalMins?.let { it <= mins } == true }.takeIf { it > 0 }?.let { mins to it }
            },
            haveCounts = HaveFilter.entries.associateWith { have ->
                all.count { card -> have.maxMissing?.let { card.missingCount <= it } ?: true }
            },
            hasOwned = hasOwned,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun setQuery(query: String) {
        queryFlow.value = query
    }

    fun toggleTag(tag: String) {
        filtersFlow.update { it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag) }
    }

    /** *Protein*: a group, *No meat or fish* ([meatFree]), or any (both off). */
    fun setProtein(group: FoodGroup?, meatFree: Boolean = false) {
        filtersFlow.update { it.copy(protein = group, meatFree = meatFree && group == null) }
    }

    fun setCarb(group: FoodGroup?) {
        filtersFlow.update { it.copy(carb = group) }
    }

    fun setMethod(method: CookMethod?) {
        filtersFlow.update { it.copy(method = method) }
    }

    fun setMaxMins(mins: Int?) {
        filtersFlow.update { it.copy(maxMins = mins) }
    }

    fun setHave(have: HaveFilter) {
        filtersFlow.update { it.copy(have = have) }
    }

    fun setSort(sort: RecipeSort) {
        sortFlow.value = sort
    }

    fun clearFilters() {
        queryFlow.value = ""
        filtersFlow.value = RecipeFilters()
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
