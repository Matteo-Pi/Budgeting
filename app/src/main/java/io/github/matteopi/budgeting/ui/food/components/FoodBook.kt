package io.github.matteopi.budgeting.ui.food.components

import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.OwnedItem
import io.github.matteopi.budgeting.core.recipes.Pantry
import io.github.matteopi.budgeting.core.recipes.PantryCoverage
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.core.recipes.RecipeNumbers
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import java.time.LocalDate

/**
 * Everything the Recipes screens work costs and nutrition out from, at one moment (docs/PLAN.md §11.2):
 * every recipe and ingredient (archived ones included, so a recipe keeps its numbers), each ingredient's
 * resolved price (the latest paid on a receipt once its product is linked, else the typed pack price),
 * the *Count optional extras* setting and what the kitchen has ([owned], docs/PLAN.md §11.11). Screens filter out
 * archived recipes themselves.
 */
data class FoodBook(
    val recipes: List<Recipe>,
    val ingredients: Map<Long, Ingredient>,
    val prices: Map<Long, ResolvedPrice>,
    val countOptionalExtras: Boolean,
    val today: LocalDate,
    val owned: Map<Long, OwnedItem> = emptyMap(),
) {
    /** [recipe] worked out for [servings] (default: its own) with this book's prices and setting. */
    fun analyse(recipe: Recipe, servings: Int = recipe.serves): RecipeNumbers =
        RecipeMaths.analyse(recipe, ingredients, prices, servings, countOptionalExtras)

    /** How much of [recipe] (at its own servings) the kitchen has. */
    fun coverage(recipe: Recipe): PantryCoverage = Pantry.coverage(recipe, ingredients, owned, countOptionalExtras)

    /** The recipes that are not archived, in the order the repository gives them (by title). */
    val liveRecipes: List<Recipe> get() = recipes.filter { !it.archived }
}