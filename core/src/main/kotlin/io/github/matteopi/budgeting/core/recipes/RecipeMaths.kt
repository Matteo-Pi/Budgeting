package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money

/** UK adult reference intakes (RIs), and the fibre guideline (docs/PLAN.md §11.2). */
object ReferenceIntakes {
    const val KJ = 8400.0
    const val KCAL = 2000.0
    const val FAT = 70.0
    const val SATURATES = 20.0
    const val CARBS = 260.0
    const val SUGARS = 90.0
    const val PROTEIN = 50.0
    const val SALT = 6.0

    /** Not an RI: the 30 g a day guideline, which the screen words that way. */
    const val FIBRE_GUIDELINE = 30.0

    /** The reference amount for [nutrient]: kcal for energy, grams otherwise (fibre: the guideline). */
    fun of(nutrient: Nutrient): Double = when (nutrient) {
        Nutrient.KCAL -> KCAL
        Nutrient.FAT -> FAT
        Nutrient.SATURATES -> SATURATES
        Nutrient.CARBS -> CARBS
        Nutrient.SUGARS -> SUGARS
        Nutrient.FIBRE -> FIBRE_GUIDELINE
        Nutrient.PROTEIN -> PROTEIN
        Nutrient.SALT -> SALT
    }

    /** [amount] (kcal or grams) as a percentage of the reference intake. */
    fun percent(nutrient: Nutrient, amount: Double): Double = amount / of(nutrient) * 100
}

/** Eight nutrient amounts: kcal, and grams of the rest. */
data class NutrientTotals(
    val kcal: Double = 0.0,
    val fat: Double = 0.0,
    val saturates: Double = 0.0,
    val carbs: Double = 0.0,
    val sugars: Double = 0.0,
    val fibre: Double = 0.0,
    val protein: Double = 0.0,
    val salt: Double = 0.0,
) {
    operator fun get(nutrient: Nutrient): Double = when (nutrient) {
        Nutrient.KCAL -> kcal
        Nutrient.FAT -> fat
        Nutrient.SATURATES -> saturates
        Nutrient.CARBS -> carbs
        Nutrient.SUGARS -> sugars
        Nutrient.FIBRE -> fibre
        Nutrient.PROTEIN -> protein
        Nutrient.SALT -> salt
    }

    /** Energy in kilojoules (kcal × 4.184). */
    val kj: Double get() = kcal * Quantities.KJ_PER_KCAL

    operator fun times(factor: Double) = NutrientTotals(
        kcal * factor, fat * factor, saturates * factor, carbs * factor, sugars * factor, fibre * factor, protein * factor, salt * factor,
    )

    operator fun div(divisor: Double) = NutrientTotals(
        kcal / divisor, fat / divisor, saturates / divisor, carbs / divisor, sugars / divisor, fibre / divisor, protein / divisor, salt / divisor,
    )

    operator fun plus(other: NutrientTotals) = NutrientTotals(
        kcal + other.kcal, fat + other.fat, saturates + other.saturates, carbs + other.carbs, sugars + other.sugars,
        fibre + other.fibre, protein + other.protein, salt + other.salt,
    )
}

/** What a breakdown measures. */
enum class BreakdownMetric { COST, KCAL, SALT, PROTEIN }

/**
 * One ingredient's share of a metric, per portion. [ingredientId] is null for the "everything else"
 * share. [fraction] is 0 to 1 of the total.
 */
data class Share(val ingredientId: Long?, val value: Double, val fraction: Double)

/**
 * Where a metric comes from: the [top] five ingredients largest first, plus [rest] ("everything
 * else", [restCount] ingredients) when there are more than five. [total] is per portion (pence for
 * cost, kcal or grams).
 */
data class Breakdown(
    val metric: BreakdownMetric,
    val total: Double,
    val top: List<Share>,
    val rest: Share?,
    val restCount: Int,
)

/** One counted line at the chosen servings. [costPence] is unrounded, null when the ingredient has no price. */
data class LineNumbers(
    val lineIndex: Int,
    val ingredientId: Long,
    val quantity: Double,
    val costPence: Double?,
)

data class CostSummary(
    /** The unrounded total for the dish in pence. */
    val totalPence: Double,
    val perPortion: Money,
    val whole: Money,
    /** Whole packs of every ingredient, bought from nothing. */
    val fromEmptyCupboard: Money,
    /** Counted ingredients with no price: they are left out, so the costs are "at least". */
    val missingPrice: List<Long>,
    /** Priced ingredients whose price is more than [Prices.OLD_AFTER_DAYS] days old. */
    val oldPrice: List<Long>,
) {
    val atLeast: Boolean get() = missingPrice.isNotEmpty()
}

data class NutritionSummary(
    val perPortion: NutrientTotals,
    /** Per 100 g of the raw counted ingredients (ml counted as g). */
    val per100: NutrientTotals,
    /** The traffic light of fat, saturates, sugars and salt. */
    val lights: Map<Nutrient, Light>,
    /** Counted ingredients with at least one unknown nutrition value: the totals are "at least". */
    val unknownIngredients: List<Long>,
    /** The nutrients that at least one counted ingredient doesn't know. */
    val unknownNutrients: Set<Nutrient>,
) {
    val atLeast: Boolean get() = unknownIngredients.isNotEmpty()

    /** Energy per portion in kJ. */
    val kj: Double get() = perPortion.kj

    /** [nutrient] per portion as a percentage of the reference intake (fibre: of the 30 g guideline). */
    fun percentRi(nutrient: Nutrient): Double = ReferenceIntakes.percent(nutrient, perPortion[nutrient])
}

/** Everything the recipe screen shows about a recipe at some number of servings. */
data class RecipeNumbers(
    val servings: Int,
    /** servings ÷ the recipe's own servings: what every quantity is multiplied by. */
    val factor: Double,
    val countOptional: Boolean,
    val cost: CostSummary,
    val nutrition: NutritionSummary,
    /** 5 a day portions of 80 g per portion, pulses capped at one. */
    val veg: Double,
    /** Grams of counted ingredients per portion, ml counted as g. */
    val rawGramsPerPortion: Double,
    val lines: List<LineNumbers>,
    val breakdowns: Map<BreakdownMetric, Breakdown>,
)

/** Cost, nutrition, veg and scaling for one recipe (docs/PLAN.md §11.2). */
object RecipeMaths {

    /** NHS 5 A Day: one portion of vegetables is 80 g. */
    const val VEG_PORTION_GRAMS = 80.0

    private const val TOP_SHARES = 5
    private const val PACK_EPSILON = 1e-9

    /** What every quantity is multiplied by to cook for [servings] instead of the recipe's own. */
    fun scaleFactor(recipe: Recipe, servings: Int): Double = servings.toDouble() / recipe.serves.coerceAtLeast(1)

    /**
     * Works out [recipe] for [servings] (default: its own).
     *
     * - A line counts when it points at one of [ingredients], has a quantity, and is not optional
     *   unless [countOptional]. Others are left out of everything.
     * - Cost is kept unrounded and divided by the servings before rounding half up, so the braise
     *   (744.996 p) is £7.45 whole but £3.72 a portion. Ingredients with no entry in [prices] are
     *   left out and listed in [CostSummary.missingPrice].
     * - Nutrition per portion = Σ quantity ÷ 100 × value per 100 ÷ servings. Unknown values count as
     *   zero and are reported.
     * - Veg = Σ veg grams ÷ servings ÷ 80, plus the pulses' grams the same way but at most 1.
     */
    fun analyse(
        recipe: Recipe,
        ingredients: Map<Long, Ingredient>,
        prices: Map<Long, ResolvedPrice>,
        servings: Int = recipe.serves,
        countOptional: Boolean = true,
    ): RecipeNumbers {
        val portions = servings.coerceAtLeast(1)
        val factor = scaleFactor(recipe, portions)

        val counted = ArrayList<LineNumbers>()
        val quantityByIngredient = LinkedHashMap<Long, Double>()
        var totalPence = 0.0
        var nutrients = NutrientTotals()
        var rawGrams = 0.0
        var vegGrams = 0.0
        var pulseGrams = 0.0
        val unknownIngredients = LinkedHashSet<Long>()
        val unknownNutrients = LinkedHashSet<Nutrient>()
        val missing = LinkedHashSet<Long>()
        val shares = BreakdownMetric.entries.associateWith { LinkedHashMap<Long, Double>() }

        recipe.lines.forEachIndexed { index, line ->
            val id = line.ingredientId ?: return@forEachIndexed
            val baseQuantity = line.quantity ?: return@forEachIndexed
            val ingredient = ingredients[id] ?: return@forEachIndexed
            if (line.optional && !countOptional) return@forEachIndexed
            val quantity = baseQuantity * factor

            val price = prices[id]
            val cost = price?.let { quantity * it.perUnitPence }
            if (cost != null) {
                totalPence += cost
                shares.getValue(BreakdownMetric.COST).merge(id, cost / portions, Double::plus)
            } else {
                missing += id
            }
            counted += LineNumbers(index, id, quantity, cost)
            quantityByIngredient.merge(id, quantity, Double::plus)

            rawGrams += quantity
            when (ingredient.vegKind) {
                VegKind.VEG -> vegGrams += quantity
                VegKind.PULSE -> pulseGrams += quantity
                VegKind.NONE -> Unit
            }

            val per100 = ingredient.per100
            fun amount(nutrient: Nutrient): Double {
                val value = per100[nutrient]
                if (value == null) {
                    unknownIngredients += id
                    unknownNutrients += nutrient
                    return 0.0
                }
                return quantity / 100.0 * value
            }
            val added = NutrientTotals(
                kcal = amount(Nutrient.KCAL),
                fat = amount(Nutrient.FAT),
                saturates = amount(Nutrient.SATURATES),
                carbs = amount(Nutrient.CARBS),
                sugars = amount(Nutrient.SUGARS),
                fibre = amount(Nutrient.FIBRE),
                protein = amount(Nutrient.PROTEIN),
                salt = amount(Nutrient.SALT),
            )
            nutrients += added
            shares.getValue(BreakdownMetric.KCAL).merge(id, added.kcal / portions, Double::plus)
            shares.getValue(BreakdownMetric.SALT).merge(id, added.salt / portions, Double::plus)
            shares.getValue(BreakdownMetric.PROTEIN).merge(id, added.protein / portions, Double::plus)
        }

        val perPortion = nutrients / portions.toDouble()
        val per100 = if (rawGrams > 0) nutrients * 100.0 / rawGrams else NutrientTotals()
        val rawPerPortion = rawGrams / portions
        val lights = TrafficLights.coloured.associateWith {
            TrafficLights.colour(it, per100[it], perPortion[it]) ?: Light.GREEN
        }
        val veg = vegGrams / portions / VEG_PORTION_GRAMS + minOf(1.0, pulseGrams / portions / VEG_PORTION_GRAMS)

        val old = LinkedHashSet<Long>()
        for (id in quantityByIngredient.keys) if (prices[id]?.old == true) old += id

        val cost = CostSummary(
            totalPence = totalPence,
            perPortion = Money(Quantities.roundHalfUp(totalPence / portions)),
            whole = Money(Quantities.roundHalfUp(totalPence)),
            fromEmptyCupboard = fromEmptyCupboard(quantityByIngredient, ingredients, prices),
            missingPrice = missing.toList(),
            oldPrice = old.toList(),
        )

        return RecipeNumbers(
            servings = portions,
            factor = factor,
            countOptional = countOptional,
            cost = cost,
            nutrition = NutritionSummary(
                perPortion = perPortion,
                per100 = per100,
                lights = lights,
                unknownIngredients = unknownIngredients.toList(),
                unknownNutrients = unknownNutrients,
            ),
            veg = veg,
            rawGramsPerPortion = rawPerPortion,
            lines = counted,
            breakdowns = BreakdownMetric.entries.associateWith { breakdown(it, shares.getValue(it)) },
        )
    }

    /** Σ over distinct ingredients of ⌈quantity ÷ pack size⌉ × pack price; ingredients with no price add nothing. */
    private fun fromEmptyCupboard(
        quantities: Map<Long, Double>,
        ingredients: Map<Long, Ingredient>,
        prices: Map<Long, ResolvedPrice>,
    ): Money {
        var pence = 0L
        for ((id, quantity) in quantities) {
            val ingredient = ingredients[id] ?: continue
            val price = prices[id] ?: continue
            if (ingredient.packSize <= 0.0) continue
            val packs = Math.ceil(quantity / ingredient.packSize - PACK_EPSILON).toLong().coerceAtLeast(0L)
            pence += packs * price.packPrice.pence
        }
        return Money(pence)
    }

    private fun breakdown(metric: BreakdownMetric, values: Map<Long, Double>): Breakdown {
        val total = values.values.sum()
        val sorted = values.entries.filter { it.value > 0.0 }.sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })
        fun share(id: Long?, value: Double) = Share(id, value, if (total > 0) value / total else 0.0)
        val top = sorted.take(TOP_SHARES).map { share(it.key, it.value) }
        val others = sorted.drop(TOP_SHARES)
        val rest = if (others.isEmpty()) null else share(null, others.sumOf { it.value })
        return Breakdown(metric, total, top, rest, others.size)
    }

    /** Shows [nutrient] the way a label does: energy to the whole number, grams to 1 decimal place. */
    fun formatNutrient(nutrient: Nutrient, value: Double): String =
        if (nutrient == Nutrient.KCAL) Quantities.roundHalfUp(value).toString() else Quantities.plain(value, 1).let {
            if ('.' in it) it else "$it.0"
        }

    /** Veg portions to 1 decimal place: "6.1". */
    fun formatVeg(veg: Double): String = Quantities.plain(veg, 1).let { if ('.' in it) it else "$it.0" }
}
