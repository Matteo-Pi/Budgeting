package io.github.matteopi.budgeting.ui.food.foods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.data.repo.IngredientRepository
import io.github.matteopi.budgeting.domain.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoodListUiState(
    val loading: Boolean = true,
    val about: String = "",
    val sections: List<FoodSectionUi> = emptyList(),
)

/**
 * Drives the food list (docs/PLAN.md §11.12): the built-in foods, searched, each saying whether the user already
 * has it; a tap adds the food as an ingredient (or finds the one they have) and opens it.
 */
class FoodListViewModel(
    private val ingredientRepository: IngredientRepository,
    private val clock: Clock,
) : ViewModel() {

    private val query = MutableStateFlow("")

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    private var adding = false

    val uiState: StateFlow<FoodListUiState> = combine(ingredientRepository.observeAll(), query) { ingredients, search ->
        val today = clock.today()
        FoodListUiState(
            loading = false,
            about = FoodListMapping.about(today),
            sections = FoodListMapping.sections(FoodDatabase.search(search), search, FoodListMapping.existing(ingredients), today),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodListUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    /** Adds the food [key] as an ingredient, or finds the one with its name, and calls [onAdded] with its id. */
    fun add(key: String, onAdded: (Long) -> Unit) {
        val food = FoodDatabase.byKey(key) ?: return
        if (adding) return
        adding = true
        viewModelScope.launch {
            try {
                onAdded(ingredientRepository.addFood(food))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableError.value = "Couldn't add ${food.name}: ${e.message ?: "unknown error"}"
            } finally {
                adding = false
            }
        }
    }

    fun errorShown() {
        mutableError.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                FoodListViewModel(app.container.ingredientRepository, app.container.clock)
            }
        }
    }
}
