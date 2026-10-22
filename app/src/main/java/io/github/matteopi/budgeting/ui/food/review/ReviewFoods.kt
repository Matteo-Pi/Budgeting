package io.github.matteopi.budgeting.ui.food.review

import io.github.matteopi.budgeting.core.recipes.Food
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import java.time.LocalDate

/**
 * The built-in food list in the review (docs/PLAN.md §11.12). A line that none of the user's ingredients fits can take
 * a food instead, under a provisional negative id, so the live numbers count it; saving the recipe adds the food as an
 * ingredient and puts the new id on the line.
 */
object ReviewFoods {

    private val foods: List<Food> by lazy { FoodDatabase.all() }

    private val ids: Map<String, Long> by lazy { foods.withIndex().associate { (index, food) -> food.key to -(index + 1L) } }

    /** Each food as an ingredient with its provisional id. */
    val ingredients: Map<Long, Ingredient> by lazy {
        foods.associate { food -> ids.getValue(food.key).let { id -> id to food.ingredient.copy(id = id) } }
    }

    fun isFood(ingredientId: Long?): Boolean = ingredientId != null && ingredientId < 0

    fun idOf(food: Food): Long = ids.getValue(food.key)

    fun food(ingredientId: Long): Food? = if (ingredientId < 0) foods.getOrNull((-ingredientId - 1).toInt()) else null

    /** The foods' typed prices at [today], by provisional id. */
    fun prices(today: LocalDate): Map<Long, ResolvedPrice> = Prices.resolveAll(ingredients.values.toList(), emptyList(), today)
}
