package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.OwnedItem
import io.github.matteopi.budgeting.core.recipes.Pantry
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import io.github.matteopi.budgeting.core.recipes.Shelf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    /** "Have 450 g · about 3 · 12 Oct", or "Have some · 12 Oct"; null when the kitchen has none (docs/PLAN.md §11.11). */
    val ownedText: String? = null,
)

/** The Ingredients tab's filter on what the kitchen has. */
enum class OwnedFilter(val label: String) { ALL("All"), OWNED("I have"), NOT_OWNED("I don't have") }

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

    /** What a row says the kitchen has: "Have 450 g · about 3 · 12 Oct" (the date it was last set). */
    fun ownedText(item: OwnedItem, ingredient: Ingredient, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(item.updatedAtEpochMillis).atZone(zone).toLocalDate()
        return "Have ${Pantry.describe(item, ingredient).replaceFirstChar { it.lowercase(Locale.ROOT) }} · ${FoodTexts.shortDate(date, today)}"
    }

    /**
     * Non-archived ingredients matching [query] and [filter] ([owned] is what the kitchen has), grouped by shelf
     * in [Shelf] order and by name (case ignored) within a shelf. Shelves with nothing on them are left out.
     */
    fun sections(
        ingredients: List<Ingredient>,
        prices: Map<Long, ResolvedPrice>,
        query: String,
        today: LocalDate,
        owned: Map<Long, OwnedItem> = emptyMap(),
        filter: OwnedFilter = OwnedFilter.ALL,
    ): List<IngredientSectionUi> {
        val shown = ingredients.filter { ingredient ->
            !ingredient.archived && matches(ingredient, query) && when (filter) {
                OwnedFilter.ALL -> true
                OwnedFilter.OWNED -> ingredient.id in owned
                OwnedFilter.NOT_OWNED -> ingredient.id !in owned
            }
        }
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
                        ownedText = owned[ingredient.id]?.let { ownedText(it, ingredient, today) },
                    )
                }
            if (rows.isEmpty()) null else IngredientSectionUi(shelf, FoodTexts.shelfName(shelf), rows)
        }
    }
}
