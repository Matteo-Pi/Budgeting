package io.github.matteopi.budgeting.core.recipes

import java.time.LocalDate

/** The starter recipe book decoded once, with its typed prices, for the tests that use it. */
object StarterBook {
    val today: LocalDate = LocalDate.of(2026, 10, 17)

    val bundle: RecipeBundle = RecipeBundle.starter()
    val models: BundleModels = bundle.toModels()
    val ingredients: List<Ingredient> = models.ingredients
    val ingredientById: Map<Long, Ingredient> = ingredients.associateBy { it.id }
    val recipes: List<Recipe> = models.recipes
    val prices: Map<Long, ResolvedPrice> = Prices.resolveAll(ingredients, emptyList(), today)

    fun recipe(key: String): Recipe = recipes.first { it.id == models.recipeKeys.getValue(key) }

    fun ingredient(key: String): Ingredient = ingredientById.getValue(models.ingredientKeys.getValue(key))

    fun numbers(key: String, countOptional: Boolean = true, servings: Int? = null): RecipeNumbers {
        val recipe = recipe(key)
        return RecipeMaths.analyse(recipe, ingredientById, prices, servings ?: recipe.serves, countOptional)
    }
}
