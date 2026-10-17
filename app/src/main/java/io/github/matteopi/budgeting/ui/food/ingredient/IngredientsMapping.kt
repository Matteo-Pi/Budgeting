package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import io.github.matteopi.budgeting.core.recipes.Shelf
import java.time.LocalDate
import java.util.Locale

/** One row of the Ingredients tab. */
data class IngredientRowUi(
    val id: Long,
    val name: String,
    /** "£0.99 · 1 kg bag · typed 15 Oct" or "paid £0.89 at Tesco 3 Nov"; null when there is no price. */
    val priceLine: String?,
    /** The price is more than 120 days old. */
    val old: Boolean,
    val noPrice: Boolean,
    val noNutrition: Boolean,
)

/** The rows of one shelf. */
data class IngredientSectionUi(val shelf: Shelf, val title: String, val rows: List<IngredientRowUi>)

/** What the Ingredients tab lists (docs/PLAN.md §11.8): pure, so it can be unit-tested. */
object IngredientsMapping {

    /** True when every word of [query] is in the ingredient's name or the shop's name for it. Blank matches everything. */
    fun matches(ingredient: Ingredient, query: String): Boolean {
        val words = query.trim().lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = (ingredient.name + " " + ingredient.productName.orEmpty()).lowercase(Locale.ROOT)
        return words.all { it in haystack }
    }

    /**
     * Non-archived ingredients matching [query], grouped by shelf in [Shelf] order and by name (case
     * ignored) within a shelf. Shelves with nothing on them are left out.
     */
    fun sections(
        ingredients: List<Ingredient>,
        prices: Map<Long, ResolvedPrice>,
        query: String,
        today: LocalDate,
    ): List<IngredientSectionUi> {
        val shown = ingredients.filter { !it.archived && matches(it, query) }
        return Shelf.entries.mapNotNull { shelf ->
            val rows = shown
                .filter { it.shelf == shelf }
                .sortedWith(compareBy<Ingredient> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
                .map { ingredient ->
                    val price = prices[ingredient.id]
                    IngredientRowUi(
                        id = ingredient.id,
                        name = ingredient.name,
                        priceLine = FoodTexts.priceLine(ingredient, price, today),
                        old = price?.old == true,
                        noPrice = price == null,
                        noNutrition = ingredient.per100.isEmpty,
                    )
                }
            if (rows.isEmpty()) null else IngredientSectionUi(shelf, FoodTexts.shelfName(shelf), rows)
        }
    }
}
