package io.github.matteopi.budgeting.ui.food.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeNumbers
import io.github.matteopi.budgeting.core.recipes.RecipeText
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecipeDetailUiState(
    val loading: Boolean = true,
    /** The recipe is gone (deleted, or a stale link). */
    val missing: Boolean = false,
    val recipe: Recipe? = null,
    val servings: Int = 1,
    val numbers: RecipeNumbers? = null,
    val lines: List<DetailLine> = emptyList(),
    val ingredients: Map<Long, Ingredient> = emptyMap(),
    val hasPulses: Boolean = false,
    val countOptionalExtras: Boolean = true,
)

/**
 * The recipe screen (docs/PLAN.md §11.8): the recipe with its numbers at the servings chosen on the stepper,
 * worked out with the same prices and *Count optional extras* setting as every other screen ([observeFoodBook]).
 * Rating and notes are saved as they change; *Add to shopping list* and *Archive* go through the repositories.
 */
class RecipeDetailViewModel(private val container: AppContainer, private val recipeId: Long) : ViewModel() {

    /** The servings on the stepper; null until the person changes them (the recipe's own servings). */
    private val servingsFlow = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<RecipeDetailUiState> = combine(container.observeFoodBook(), servingsFlow) { book, chosen ->
        val recipe = book.recipes.firstOrNull { it.id == recipeId }
        if (recipe == null) {
            RecipeDetailUiState(loading = false, missing = true)
        } else {
            val servings = chosen ?: recipe.serves
            RecipeDetailUiState(
                loading = false,
                recipe = recipe,
                servings = servings,
                numbers = book.analyse(recipe, servings),
                lines = RecipeDetailMapping.lines(recipe, book.ingredients, servings),
                ingredients = book.ingredients,
                hasPulses = RecipeDetailMapping.hasPulses(recipe, book.ingredients),
                countOptionalExtras = book.countOptionalExtras,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeDetailUiState())

    fun setServings(servings: Int) {
        servingsFlow.value = servings.coerceIn(1, 24)
    }

    fun setRating(rating: Int) {
        container.appScope.launch { container.recipeRepository.setRating(recipeId, rating) }
    }

    fun setNotes(notes: String) {
        container.appScope.launch { container.recipeRepository.setNotes(recipeId, notes) }
    }

    fun setCountOptionalExtras(count: Boolean) {
        container.appScope.launch { container.settingsStore.setCountOptionalExtras(count) }
    }

    /** *Add to shopping list* for [servings]; [onAdded] runs once it is saved. */
    fun addToShoppingList(servings: Int, onAdded: () -> Unit) {
        viewModelScope.launch {
            container.shoppingRepository.addRecipe(recipeId, servings.coerceIn(1, 99))
            onAdded()
        }
    }

    /** *Archive*: the recipe leaves the lists but its lines keep their ingredients. */
    fun archive(onDone: () -> Unit) {
        viewModelScope.launch {
            container.recipeRepository.setArchived(recipeId, true)
            onDone()
        }
    }

    /** The recipe in the paste format, for *Share as text*. */
    fun shareText(): String? = uiState.value.recipe?.let { RecipeText.format(it) }

    companion object {
        fun factory(recipeId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                RecipeDetailViewModel(app.container, recipeId)
            }
        }
    }
}
