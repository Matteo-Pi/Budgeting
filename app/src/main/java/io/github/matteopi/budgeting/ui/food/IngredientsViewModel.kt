package io.github.matteopi.budgeting.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.data.repo.IngredientRepository
import io.github.matteopi.budgeting.data.repo.ReceiptRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientSectionUi
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientsMapping
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class IngredientsUiState(
    val loading: Boolean = true,
    /** Shelves in shop order, each with its ingredients by name; only those matching the search. */
    val sections: List<IngredientSectionUi> = emptyList(),
    /** True when there is at least one ingredient that isn't archived, whatever the search says. */
    val hasAny: Boolean = false,
)

/**
 * Drives the Ingredients tab (docs/PLAN.md §11.8): the ingredients with the price they cost at today
 * (`Prices.resolveAll`, so a price paid on a receipt wins over the typed one), filtered by the search.
 */
class IngredientsViewModel(
    ingredientRepository: IngredientRepository,
    receiptRepository: ReceiptRepository,
    private val clock: Clock,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<IngredientsUiState> = combine(
        ingredientRepository.observeAll(),
        receiptRepository.observeReceipts(),
        receiptRepository.observeAllItems(),
        query,
    ) { ingredients, receipts, items, search ->
        val today = clock.today()
        val prices = Prices.resolveAll(ingredients, ReceiptMapping.purchasedItems(receipts, items), today)
        IngredientsUiState(
            loading = false,
            sections = IngredientsMapping.sections(ingredients, prices, search, today),
            hasAny = ingredients.any { !it.archived },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IngredientsUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                val container = app.container
                IngredientsViewModel(container.ingredientRepository, container.receiptRepository, container.clock)
            }
        }
    }
}
