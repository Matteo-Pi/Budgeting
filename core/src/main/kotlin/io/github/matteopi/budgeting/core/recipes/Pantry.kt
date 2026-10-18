package io.github.matteopi.budgeting.core.recipes

/** A unit the *How much I have* dialog offers: [factor] base units (g or ml) per one of it. */
data class PantryUnit(val label: String, val factor: Double)

/**
 * How much of a recipe the kitchen covers: of its [needed] distinct ingredients, [have] are owned in at least the
 * recipe's quantity (or owned unmeasured); [missing] are the rest, in the recipe's order.
 */
data class PantryCoverage(val needed: Int, val have: Int, val missing: List<Long>) {
    val missingCount: Int get() = missing.size
}

/** What the person has in the kitchen (docs/PLAN.md §11.11): entering amounts, showing them, and checking recipes. */
object Pantry {

    private const val EPSILON = 1e-6

    /** Portion names that are spoon sizes for converting, not things anyone counts in a cupboard. */
    private val SPOON_NAMES = setOf("tsp", "tbsp", "teaspoon", "tablespoon", "pinch", "cup")

    /** The units for [ingredient]: g and kg (or ml and l), packs of its pack size, then its portions such as "each". */
    fun units(ingredient: Ingredient): List<PantryUnit> {
        val small = if (ingredient.unit == BaseUnit.G) "g" else "ml"
        val big = if (ingredient.unit == BaseUnit.G) "kg" else "l"
        val units = mutableListOf(PantryUnit(small, 1.0), PantryUnit(big, 1000.0))
        if (ingredient.packSize > 0.0) {
            units += PantryUnit("packs of ${Quantities.format(ingredient.packSize, ingredient.unit)}", ingredient.packSize)
        }
        for (portion in ingredient.portions) {
            if (portion.amount <= 0.0 || portion.name.trim().lowercase() in SPOON_NAMES) continue
            units += PantryUnit("${portion.name} (${Quantities.format(portion.amount, ingredient.unit)})", portion.amount)
        }
        return units
    }

    /** [amount] of [unit] in base units, or null when it isn't above zero. */
    fun toBase(amount: Double, unit: PantryUnit): Double? = (amount * unit.factor).takeIf { it > 0.0 && it.isFinite() }

    /**
     * What is owned, for a row: "450 g · about 3" (with an "each" portion), "1.5 kg · about 1½ packs", "300 g", or
     * "Some" when it wasn't measured.
     */
    fun describe(item: OwnedItem, ingredient: Ingredient): String {
        val quantity = item.quantity ?: return "Some"
        val base = Quantities.format(quantity, ingredient.unit)
        val each = Quantities.portion(ingredient, "each")
        val about = when {
            each != null && each.amount > 0.0 -> halves(quantity / each.amount)?.let { "about $it" }
            ingredient.packSize > 0.0 && quantity >= ingredient.packSize / 2 ->
                halves(quantity / ingredient.packSize)?.let { "about $it ${if (it == "1") "pack" else "packs"}" }
            else -> null
        }
        return if (about == null) base else "$base · $about"
    }

    /** [value] to the nearest ½ ("1½"), or null when that is 0. */
    private fun halves(value: Double): String? {
        val rounded = Math.round(value * 2) / 2.0
        return if (rounded <= 0.0) null else Quantities.formatCount(rounded)
    }

    /**
     * How much of [recipe] (at its own servings) [owned] covers. Each distinct ingredient of a line that points at
     * one of [ingredients] counts once (optional lines only when [countOptional]); a line with no quantity needs
     * the ingredient to be owned at all. Owned with no quantity covers any amount.
     */
    fun coverage(
        recipe: Recipe,
        ingredients: Map<Long, Ingredient>,
        owned: Map<Long, OwnedItem>,
        countOptional: Boolean = true,
    ): PantryCoverage {
        val need = LinkedHashMap<Long, Double>()
        for (line in recipe.lines) {
            val id = line.ingredientId ?: continue
            if (id !in ingredients) continue
            if (line.optional && !countOptional) continue
            need.merge(id, line.quantity ?: 0.0, Double::plus)
        }
        val missing = need.filter { (id, quantity) -> !covers(owned[id], quantity) }.keys.toList()
        return PantryCoverage(needed = need.size, have = need.size - missing.size, missing = missing)
    }

    /** True when [item] covers [quantity] base units. */
    fun covers(item: OwnedItem?, quantity: Double): Boolean {
        if (item == null) return false
        val have = item.quantity ?: return true
        return have >= quantity - EPSILON
    }
}
