package io.github.matteopi.budgeting.ui.food.foods

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.recipes.Food
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.ui.food.ingredient.FoodTexts
import java.time.LocalDate
import java.util.Locale

/** One food of the food list. */
@Immutable
data class FoodRowUi(
    val key: String,
    val name: String,
    /** "£0.41 · 400 g tin (240 g drained) · Tesco 22 Oct". */
    val priceLine: String,
    /** "121 kcal · 7.0 g protein per 100 g". */
    val nutritionLine: String,
    /** The user's ingredient with the food's name, if there is one: the row says so and opens it. */
    val existingId: Long?,
)

/** The foods of one shelf, or ([shelf] null) the foods a search found, best first. */
@Immutable
data class FoodSectionUi(val shelf: Shelf?, val title: String?, val rows: List<FoodRowUi>)

/** The food list screen (docs/PLAN.md §11.12): pure, so it can be unit-tested. */
object FoodListMapping {

    /** What the list says at the top: where the numbers come from. */
    fun about(today: LocalDate): String =
        "Tesco prices found online on ${FoodTexts.shortDate(FoodDatabase.PRICES_FOUND, today)} (not Clubcard) and typical " +
            "nutrition for the food as weighed in recipes. Tap a food to add it to your ingredients; you can change " +
            "anything after."

    fun row(food: Food, existing: Map<String, Long>, today: LocalDate): FoodRowUi {
        val ingredient = food.ingredient
        val price = ingredient.price?.format() ?: "no price"
        val date = ingredient.priceDate?.let { " ${FoodTexts.shortDate(it, today)}" }.orEmpty()
        val per100 = ingredient.per100
        val unit = FoodTexts.unitSymbol(ingredient.unit)
        return FoodRowUi(
            key = food.key,
            name = food.name,
            priceLine = "$price · ${FoodTexts.packText(ingredient)} · ${ingredient.store ?: "Tesco"}$date",
            nutritionLine = "${per100.kcal?.let { RecipeMaths.formatNutrient(Nutrient.KCAL, it) } ?: "?"} kcal · " +
                "${per100.protein?.let { RecipeMaths.formatNutrient(Nutrient.PROTEIN, it) } ?: "?"} g protein per 100 $unit",
            existingId = existing[nameKey(food.name)],
        )
    }

    /** The user's ingredients (not archived) by their name in lower case, for *Already in your ingredients*. */
    fun existing(ingredients: List<Ingredient>): Map<String, Long> =
        ingredients.filter { !it.archived }.groupBy { nameKey(it.name) }.mapValues { (_, same) -> same.minOf { it.id } }

    /**
     * A blank [query] gives every food, by shelf in shop order and by name within it; otherwise one list of what
     * `FoodDatabase.search` finds, best first.
     */
    fun sections(foods: List<Food>, query: String, existing: Map<String, Long>, today: LocalDate): List<FoodSectionUi> {
        if (query.isBlank()) {
            return Shelf.entries.mapNotNull { shelf ->
                val rows = foods.filter { it.ingredient.shelf == shelf }.sortedBy { it.name.lowercase(Locale.ROOT) }
                    .map { row(it, existing, today) }
                if (rows.isEmpty()) null else FoodSectionUi(shelf, FoodTexts.shelfName(shelf), rows)
            }
        }
        val rows = foods.map { row(it, existing, today) }
        return if (rows.isEmpty()) emptyList() else listOf(FoodSectionUi(null, null, rows))
    }

    private fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)
}
