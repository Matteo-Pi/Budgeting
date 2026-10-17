package io.github.matteopi.budgeting.ui.food

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.ShoppingList
import io.github.matteopi.budgeting.core.recipes.ShoppingListResult
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import io.github.matteopi.budgeting.ui.food.ingredient.ShoppingTexts
import io.github.matteopi.budgeting.ui.food.scan.FoodScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A recipe on the shopping list: one entry, with the servings it was added for. */
data class ShoppingRecipeUi(val entryId: Long, val recipeId: Long, val name: String, val servings: Int)

data class ShoppingUiState(
    val loading: Boolean = true,
    val list: ShoppingListResult? = null,
    val recipes: List<ShoppingRecipeUi> = emptyList(),
    /** Recipe names by id, for "For Stir-fry, Traybake". */
    val recipeNames: Map<Long, String> = emptyMap(),
    /** The ingredients an extra item can be (not archived), by name. */
    val ingredients: List<Ingredient> = emptyList(),
) {
    val isEmpty: Boolean get() = list?.isEmpty != false && recipes.isEmpty()
}

/**
 * The Shopping tab (docs/PLAN.md §11.6): the recipes on the list and the extra items, summed per ingredient by
 * [ShoppingList.build] with the same prices and *Count optional extras* setting as every other screen.
 */
class ShoppingViewModel(private val container: AppContainer) : ViewModel() {
    private val shopping = container.shoppingRepository

    val uiState: StateFlow<ShoppingUiState> = combine(
        container.observeFoodBook(),
        shopping.observeEntries(),
        shopping.observeMarks(),
    ) { book, entries, marks ->
        val recipeById = book.recipes.associateBy { it.id }
        val list = ShoppingList.build(
            entries = entries,
            marks = marks,
            recipes = book.recipes,
            ingredients = book.ingredients.values.toList(),
            prices = book.prices,
            countOptional = book.countOptionalExtras,
        )
        ShoppingUiState(
            loading = false,
            list = list,
            recipes = entries.mapNotNull { entry ->
                val recipe = entry.recipeId?.let { recipeById[it] } ?: return@mapNotNull null
                ShoppingRecipeUi(entry.id, recipe.id, ShoppingTexts.recipeName(recipe), entry.servings ?: recipe.serves)
            },
            recipeNames = book.recipes.associate { it.id to ShoppingTexts.recipeName(it) },
            ingredients = book.ingredients.values.filter { !it.archived }.sortedBy { it.name.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingUiState())

    private fun launch(block: suspend () -> Unit) {
        container.appScope.launch { block() }
    }

    fun setChecked(ingredientId: Long, checked: Boolean) = launch { shopping.setChecked(ingredientId, checked) }

    fun setHaveIt(ingredientId: Long, haveIt: Boolean) = launch { shopping.setHaveIt(ingredientId, haveIt) }

    fun setFreeTextChecked(entryId: Long, checked: Boolean) = launch { shopping.setFreeTextChecked(entryId, checked) }

    fun removeEntry(entryId: Long) = launch { shopping.removeEntry(entryId) }

    fun setServings(entryId: Long, servings: Int) = launch { shopping.setServings(entryId, servings.coerceIn(1, 99)) }

    /** An extra item from the ingredients; a null [quantity] is one whole pack. */
    fun addIngredient(ingredientId: Long, quantity: Double?) = launch { shopping.addIngredient(ingredientId, quantity) }

    fun addFreeText(text: String) = launch { shopping.addFreeText(text) }

    /** *Clear ticked*: ticked free-text items go, and every tick and "I have it" is reset. */
    fun clearTicked() = launch { shopping.clearTicked() }

    /** *Clear list*: everything. */
    fun clearList() = launch { shopping.clearList() }

    /**
     * Scanning a pack in the shop (docs/PLAN.md §11.6): the barcode in the photo at [uri] ticks the line of its
     * ingredient; [onResult] says what happened.
     */
    fun scanToTick(context: Context, uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val code = try {
                FoodScan.readBarcode(context, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (code == null) {
                onResult("No barcode found in the photo. Try again with the whole barcode in view.")
                return@launch
            }
            val ingredient = container.ingredientRepository.findByBarcode(code)
            val line = ingredient?.let { found -> uiState.value.list?.lines?.firstOrNull { it.ingredientId == found.id } }
            when {
                ingredient == null -> onResult("No ingredient has this barcode yet. Scan it on the Ingredients tab to add it.")
                line == null -> onResult("${ingredient.name} isn't on the list.")
                else -> {
                    shopping.setChecked(ingredient.id, true)
                    onResult("Ticked ${ingredient.name}")
                }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                ShoppingViewModel(app.container)
            }
        }
    }
}
