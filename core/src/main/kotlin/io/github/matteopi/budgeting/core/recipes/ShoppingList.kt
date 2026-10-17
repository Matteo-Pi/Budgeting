package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money

/** One ingredient line of the shopping list: everything that needs the ingredient, summed. */
data class ShoppingLine(
    val ingredientId: Long,
    val name: String,
    val shelf: Shelf,
    val unit: BaseUnit,
    /** The total needed, in [unit]. */
    val quantity: Double,
    val packSize: Double,
    val packLabel: String,
    /** Whole packs to buy: ⌈quantity ÷ pack size⌉, or 0 when [haveIt]. */
    val packs: Int,
    val price: ResolvedPrice?,
    /** [packs] × the pack price; null when there is no price. */
    val cost: Money?,
    val checked: Boolean,
    val haveIt: Boolean,
    /** The recipes that need it (in the order they were added). */
    val recipeIds: List<Long>,
    /** True when an extra item (not a recipe) asked for it too. */
    val isExtra: Boolean,
)

/** An extra item typed as text. */
data class ShoppingFreeLine(val entryId: Long, val text: String, val checked: Boolean)

/** The lines of one shelf. */
data class ShelfSection(val shelf: Shelf, val lines: List<ShoppingLine>)

/**
 * The header: "About £18.40 for 9 items", and "£12.10 of it goes into these meals".
 *
 * [items] counts the ingredient lines still to buy (not marked "I have it"); free-text items are
 * counted apart in [freeTextItems] because they have no price. [unpricedItems] of those have no
 * price, which makes [estimated] "at least". [mealsCost] is the recipes' own unrounded cost at the
 * servings chosen, rounded once; [mealsAtLeast] when one of their ingredients has no price.
 */
data class ShoppingHeader(
    val items: Int,
    val estimated: Money,
    val unpricedItems: Int,
    val mealsCost: Money,
    val mealsAtLeast: Boolean,
    val freeTextItems: Int,
) {
    val estimateAtLeast: Boolean get() = unpricedItems > 0
}

data class ShoppingListResult(
    /** Shelves in shop order; shelves with nothing on them are left out. */
    val sections: List<ShelfSection>,
    /** Free-text items, at the end. */
    val freeText: List<ShoppingFreeLine>,
    val header: ShoppingHeader,
) {
    val isEmpty: Boolean get() = sections.isEmpty() && freeText.isEmpty()
    val lines: List<ShoppingLine> get() = sections.flatMap { it.lines }
}

/** Building the shopping list from its entries (docs/PLAN.md §11.6). */
object ShoppingList {

    private const val PACK_EPSILON = 1e-9

    private class Need(val ingredient: Ingredient) {
        var quantity = 0.0
        val recipeIds = LinkedHashSet<Long>()
        var extra = false
    }

    /**
     * Sums every ingredient across [entries], scaled, and groups the result by shelf.
     *
     * - A recipe entry adds each counted line of its recipe times servings ÷ the recipe's own
     *   servings ([countOptional] decides whether optional lines count). An extra item adds its
     *   quantity (a whole pack when it has none). Entries whose recipe or ingredient isn't in the
     *   given lists are skipped; recipe lines with no ingredient are not listed.
     * - Packs = ⌈total ÷ pack size − 1e-9⌉, or 0 when the ingredient is marked "I have it" in [marks].
     *   Cost = packs × the resolved pack price from [prices].
     * - Shelves are in shop order, ingredients by name within a shelf. Free-text entries come last.
     */
    fun build(
        entries: List<ShoppingEntry>,
        marks: List<ShoppingMark>,
        recipes: List<Recipe>,
        ingredients: List<Ingredient>,
        prices: Map<Long, ResolvedPrice>,
        countOptional: Boolean = true,
    ): ShoppingListResult {
        val recipeById = recipes.associateBy { it.id }
        val ingredientById = ingredients.associateBy { it.id }
        val markById = marks.associateBy { it.ingredientId }
        val needs = LinkedHashMap<Long, Need>()
        val freeText = ArrayList<ShoppingFreeLine>()
        var mealsPence = 0.0
        var mealsAtLeast = false

        for (entry in entries) {
            val recipe = entry.recipeId?.let { recipeById[it] }
            when {
                entry.recipeId != null -> {
                    if (recipe == null) continue
                    val servings = (entry.servings ?: recipe.serves).coerceAtLeast(1)
                    val factor = RecipeMaths.scaleFactor(recipe, servings)
                    for (line in recipe.lines) {
                        val id = line.ingredientId ?: continue
                        val quantity = line.quantity ?: continue
                        val ingredient = ingredientById[id] ?: continue
                        if (line.optional && !countOptional) continue
                        val need = needs.getOrPut(id) { Need(ingredient) }
                        need.quantity += quantity * factor
                        need.recipeIds += recipe.id
                    }
                    val numbers = RecipeMaths.analyse(recipe, ingredientById, prices, servings, countOptional)
                    mealsPence += numbers.cost.totalPence
                    if (numbers.cost.atLeast) mealsAtLeast = true
                }
                entry.ingredientId != null -> {
                    val ingredient = ingredientById[entry.ingredientId] ?: continue
                    val need = needs.getOrPut(ingredient.id) { Need(ingredient) }
                    need.quantity += entry.quantity ?: ingredient.packSize
                    need.extra = true
                }
                !entry.freeText.isNullOrBlank() -> freeText += ShoppingFreeLine(entry.id, entry.freeText.trim(), entry.checked)
            }
        }

        val lines = needs.values.filter { it.quantity > 0.0 }.map { need ->
            val ingredient = need.ingredient
            val mark = markById[ingredient.id]
            val haveIt = mark?.haveIt ?: false
            val packs = if (haveIt || ingredient.packSize <= 0.0) {
                0
            } else {
                Math.ceil(need.quantity / ingredient.packSize - PACK_EPSILON).toInt().coerceAtLeast(0)
            }
            val price = prices[ingredient.id]
            ShoppingLine(
                ingredientId = ingredient.id,
                name = ingredient.name,
                shelf = ingredient.shelf,
                unit = ingredient.unit,
                quantity = need.quantity,
                packSize = ingredient.packSize,
                packLabel = ingredient.packLabel,
                packs = packs,
                price = price,
                cost = price?.let { it.packPrice * packs },
                checked = mark?.checked ?: false,
                haveIt = haveIt,
                recipeIds = need.recipeIds.toList(),
                isExtra = need.extra,
            )
        }

        val sections = Shelf.entries.mapNotNull { shelf ->
            val onShelf = lines.filter { it.shelf == shelf }.sortedBy { it.name.lowercase() }
            if (onShelf.isEmpty()) null else ShelfSection(shelf, onShelf)
        }

        val toBuy = lines.filter { it.packs > 0 }
        val header = ShoppingHeader(
            items = toBuy.size,
            estimated = Money(toBuy.sumOf { it.cost?.pence ?: 0L }),
            unpricedItems = toBuy.count { it.cost == null },
            mealsCost = Money(Quantities.roundHalfUp(mealsPence)),
            mealsAtLeast = mealsAtLeast,
            freeTextItems = freeText.size,
        )
        return ShoppingListResult(sections, freeText, header)
    }
}
