package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.ShoppingHeader
import io.github.matteopi.budgeting.core.recipes.ShoppingLine

/** How the shopping list words its lines and header (docs/PLAN.md §11.6). Pure, so the wording can be unit-tested. */
object ShoppingTexts {

    /** "About £18.40 for 9 items". */
    fun headerLine(header: ShoppingHeader): String =
        "About ${header.estimated.format()} for ${FoodTexts.items(header.items)}"

    /** "£12.10 of it goes into these meals". Null when no recipe is on the list. */
    fun mealsLine(header: ShoppingHeader, hasRecipes: Boolean): String? =
        if (hasRecipes) "${header.mealsCost.format()} of it goes into these meals" else null

    /** "2 items have no price". Null when every item has one. */
    fun unpricedLine(header: ShoppingHeader): String? = when (header.unpricedItems) {
        0 -> null
        1 -> "1 item has no price"
        else -> "${header.unpricedItems} items have no price"
    }

    /**
     * "640 g · 2 packs of 320 g"; "300 g · you have it" once marked; with what the kitchen has (docs/PLAN.md §11.11),
     * "800 g · you have 400 g · 1 pack of 500 g", "1 kg · you have 1.2 kg, enough" or "300 g · you have some · 1 pack of 1 kg".
     */
    fun quantityLine(line: ShoppingLine): String {
        val needed = Quantities.format(line.quantity, line.unit)
        val packSize = Quantities.format(line.packSize, line.unit)
        val owned = line.owned
        return when {
            line.haveIt -> "$needed · you have it"
            line.haveEnough -> "$needed · you have ${Quantities.format(requireNotNull(owned?.quantity), line.unit)}, enough"
            owned != null -> {
                val have = owned.quantity?.let { Quantities.format(it, line.unit) } ?: "some"
                "$needed · you have $have · ${FoodTexts.packs(line.packs)} of $packSize"
            }
            else -> "$needed · ${FoodTexts.packs(line.packs)} of $packSize"
        }
    }

    /** The estimated cost of the line, "No price" when it has none, nothing once marked "I have it" or when what is owned covers it. */
    fun costText(line: ShoppingLine): String? = when {
        line.haveIt || line.haveEnough -> null
        else -> line.cost?.format() ?: "No price"
    }

    /** The name of a recipe on the list: its short title, else its title. */
    fun recipeName(recipe: Recipe): String = recipe.shortTitle?.takeIf { it.isNotBlank() } ?: recipe.title

    /** "For Stir-fry, Traybake", plus "an extra item" when something asked for it on its own. */
    fun neededFor(line: ShoppingLine, recipeNames: Map<Long, String>): String? {
        val parts = line.recipeIds.mapNotNull { recipeNames[it] }.toMutableList()
        if (line.isExtra) parts += "extra item"
        return if (parts.isEmpty()) null else "For " + parts.joinToString(", ")
    }

    /** "2 servings" / "1 serving". */
    fun servings(count: Int): String = if (count == 1) "1 serving" else "$count servings"
}
