package io.github.matteopi.budgeting.ui.food.components

import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.core.recipes.RecipeNumbers
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import java.time.LocalDate

/**
 * Everything the Recipes screens work costs and nutrition out from, at one moment (docs/PLAN.md §11.2):
 * every recipe and ingredient (archived ones included, so a recipe keeps its numbers), each ingredient's
 * resolved price (the latest paid on a receipt once its product is linked, else the typed pack price),
 * and the *Count optional extras* setting. Screens filter out archived recipes themselves.
 */
data class FoodBook(
    val recipes: List<Recipe>,
    val ingredients: Map<Long, Ingredient>,
    val prices: Map<Long, ResolvedPrice>,
    val countOptionalExtras: Boolean,
    val today: LocalDate,
) {
    /** [recipe] worked out for [servings] (default: its own) with this book's prices and setting. */
    fun analyse(recipe: Recipe, servings: Int = recipe.serves): RecipeNumbers =
        RecipeMaths.analyse(recipe, ingredients, prices, servings, countOptionalExtras)

    /** The recipes that are not archived, in the order the repository gives them (by title). */
    val liveRecipes: List<Recipe> get() = recipes.filter { !it.archived }
}