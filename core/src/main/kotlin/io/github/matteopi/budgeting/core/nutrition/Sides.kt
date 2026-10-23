package io.github.matteopi.budgeting.core.nutrition

import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.FoodGroupKind
import io.github.matteopi.budgeting.core.recipes.FoodGroups
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientMatching
import io.github.matteopi.budgeting.core.recipes.MatchKind
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.Recipe

/** An amount of an ingredient in its base unit, and how it reads ("2 slices", "75 g"). */
data class FoodAmount(val amount: Double, val text: String)

/**
 * A side the Log food screen offers: one of the user's ingredients ([ingredient] has an id), or a food of the food
 * list ([foodKey] set, id 0), which is added to the ingredients when it is logged.
 */
data class SideSuggestion(val ingredient: Ingredient, val foodKey: String? = null, val amount: FoodAmount)

/** The carbs on the side of a portion: how much by default, and which to offer (docs/PLAN.md §14.4). */
object Sides {

    /** Dried rice, pasta, noodles and grains: grams for one person. */
    const val DRY_GRAMS = 75.0

    /** A pouch of ready-cooked rice or grains. */
    const val POUCH_GRAMS = 250.0
    const val POTATO_GRAMS = 200.0
    const val CHIPS_GRAMS = 150.0
    const val OTHER_GRAMS = 100.0

    /** Spreads: a knob (10 g) on toast. */
    private val SPREADS = Regex("""(?i)\b(butter|spread|margarine)\b(?!\s*beans)""")

    /** Jams, honey, nut butters and the like: a heaped teaspoon (15 g). */
    private val TOPPINGS = Regex("""(?i)\b(jam|marmalade|honey|peanut butter|chocolate spread|hazelnut spread|syrup|lemon curd|yeast extract|marmite)\b""")

    /** Breakfast cereals: a 40 g bowl. */
    private val CEREALS = Regex("""(?i)\b(cereal|(?<!chilli )flakes|muesli|granola|porridge|oats|bran|wheat biscuits|rice snaps|crispies)\b""")
    private val MILK = Regex("""(?i)\bmilk\b""")
    private val NOT_MILK = Regex("""(?i)\b(coconut|condensed|evaporated|chocolate|powder|yogh?urt)\b""")

    const val SPREAD_GRAMS = 10.0
    const val TOPPING_GRAMS = 15.0
    const val CEREAL_GRAMS = 40.0
    const val MILK_ML = 125.0

    /** What goes with a food (docs/PLAN.md §14.5): milk on cereal, butter and jam on toast. */
    private val GOES_WITH: List<Pair<Regex, List<String>>> = listOf(
        CEREALS to listOf("semi-skimmed milk"),
        Regex("""(?i)\b(bread|toast|bagels?|crumpets?|muffins?|rolls?|sourdough)\b""") to listOf("butter", "strawberry jam", "peanut butter", "spread"),
        Regex("""(?i)\b(pancakes?|waffles?)\b""") to listOf("clear honey", "strawberry jam"),
        Regex("""(?i)\b(yogh?urt)\b""") to listOf("granola", "clear honey"),
        Regex("""(?i)\b(baking potatoes|jacket)\b""") to listOf("baked beans", "cheddar", "butter"),
    )

    private val COOKED = Regex("""(?i)\b(microwave|cooked|pouch|ready|straight to wok|express)\b""")
    private val CHIPS = Regex("""(?i)\b(chips|wedges|fries)\b""")
    private val SPOONS = setOf("tsp", "tbsp", "pinch", "spray", "handful")
    private val SPLIT = Regex("""\s*(?:,|/|;|&|\+|\bor\b|\band\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
    private val FILLER = Regex("""(?i)\b(to serve|serve|served|some|plenty of|lots of|a little|optional|on the side|of|plain|fluffy|warm|crusty|steamed|boiled|cooked)\b""")

    /** *Served with* text that says the carb is already in the recipe ("included in the numbers", "no rice needed"). */
    private val ALREADY_IN = Regex("""(?i)\b(included|no \w+ needed|not needed|is the carb|are the carb)\b""")

    /** True when [ingredient] counts as a carb (rice, pasta, noodles, potatoes, bread, grains). */
    fun isCarb(ingredient: Ingredient): Boolean = FoodGroups.of(ingredient)?.kind == FoodGroupKind.CARB

    /**
     * The default amount of [ingredient] on the side: rice, pasta, noodles and grains 75 g dried, or one pouch or
     * pack (250 g when the name says microwave or cooked); bread 2 slices, or one naan, wrap, pitta or roll;
     * potatoes 200 g, chips and wedges 150 g; anything else one of its portions, else 100 g.
     */
    fun defaultAmount(ingredient: Ingredient): FoodAmount {
        fun portion(vararg names: String): Portion? = names.firstNotNullOfOrNull { Quantities.portion(ingredient, it) }
        fun of(portion: Portion, count: Double = 1.0) = (portion.amount * count).let { FoodAmount(it, describe(ingredient, it)) }
        fun grams(amount: Double) = FoodAmount(amount, describe(ingredient, amount))
        return when (FoodGroups.of(ingredient)) {
            FoodGroup.RICE, FoodGroup.PASTA, FoodGroup.NOODLES, FoodGroup.GRAINS -> {
                portion("pouch", "pack", "nest")?.let { of(it) }
                    ?: if (COOKED.containsMatchIn(ingredient.name)) grams(POUCH_GRAMS) else grams(DRY_GRAMS)
            }
            FoodGroup.BREAD -> portion("slice")?.let { of(it, 2.0) }
                ?: portion("naan", "wrap", "pitta", "roll", "each")?.let { of(it) }
                ?: grams(OTHER_GRAMS)
            FoodGroup.POTATOES -> grams(if (CHIPS.containsMatchIn(ingredient.name)) CHIPS_GRAMS else POTATO_GRAMS)
            else -> when {
                TOPPINGS.containsMatchIn(ingredient.name) -> grams(TOPPING_GRAMS)
                SPREADS.containsMatchIn(ingredient.name) -> grams(SPREAD_GRAMS)
                CEREALS.containsMatchIn(ingredient.name) -> portion("biscuit")?.let { of(it, 2.0) } ?: grams(CEREAL_GRAMS)
                MILK.containsMatchIn(ingredient.name) && !NOT_MILK.containsMatchIn(ingredient.name) -> grams(MILK_ML)
                else -> ingredient.portions.firstOrNull { Quantities.stem(it.name) !in SPOONS.map(Quantities::stem) }?.let { of(it) }
                    ?: grams(OTHER_GRAMS)
            }
        }
    }

    /**
     * [amount] of [ingredient] as it reads: a whole or half number of one of its portions ("2 slices", "½ naan",
     * "1 pouch"; an `each` portion reads "2 (120 g)"), else grams or millilitres.
     */
    fun describe(ingredient: Ingredient, amount: Double): String {
        val portions = ingredient.portions.filter { Quantities.stem(it.name) !in SPOONS.map(Quantities::stem) && it.amount > 0 }
            .sortedBy { if (it.name.equals("each", ignoreCase = true)) 1 else 0 }
        for (portion in portions) {
            val count = amount / portion.amount
            val halves = count * 2
            if (count >= 0.5 - 1e-9 && count <= 20 && Math.abs(halves - Math.round(halves)) < 1e-6) {
                val number = Quantities.formatCount(Math.round(halves) / 2.0)
                return if (portion.name.equals("each", ignoreCase = true)) {
                    "$number (${Quantities.format(amount, ingredient.unit)})"
                } else {
                    "$number ${if (count > 1 + 1e-9) plural(portion.name) else portion.name}"
                }
            }
        }
        return Quantities.format(amount, ingredient.unit)
    }

    /** "slice" -> "slices", "pouch" -> "pouches", "leaf" -> "leaves". */
    fun plural(word: String): String = when {
        word.endsWith("leaf") -> word.dropLast(1) + "ves"
        word.endsWith("s") || word.endsWith("x") || word.endsWith("ch") || word.endsWith("sh") -> word + "es"
        else -> word + "s"
    }

    /**
     * The sides to offer with [recipe], best first and at most [limit]: what its *Served with* says (matched to an
     * ingredient, else to a food of the food list; not when it says the carb is already in), then the sides last eaten with it in [history] (newest first),
     * then the carbs logged most often. Archived ingredients are left out.
     */
    fun suggestions(
        recipe: Recipe?,
        ingredients: List<Ingredient>,
        history: List<LogItem>,
        limit: Int = 6,
        useFoodList: Boolean = true,
    ): List<SideSuggestion> {
        val live = ingredients.filter { !it.archived }
        val byId = live.associateBy { it.id }
        val out = LinkedHashMap<String, SideSuggestion>()
        fun add(ingredient: Ingredient, foodKey: String? = null, amount: FoodAmount = defaultAmount(ingredient)) {
            val key = if (ingredient.id != 0L) "i${ingredient.id}" else "f$foodKey"
            if (key !in out) out[key] = SideSuggestion(ingredient, foodKey, amount)
        }

        recipe?.servedWith?.takeIf { !ALREADY_IN.containsMatchIn(it) }?.let { text ->
            for (piece in servedWithPieces(text)) {
                val match = IngredientMatching.match(piece, live)
                val id = match.ingredientId.takeIf { match.kind != MatchKind.NONE && match.kind != MatchKind.SUGGESTIONS }
                val ingredient = id?.let { byId[it] }
                if (ingredient != null) {
                    add(ingredient)
                } else if (useFoodList) {
                    val food = FoodDatabase.match(piece) ?: continue
                    val same = live.firstOrNull { IngredientMatching.nameKey(it.name) == IngredientMatching.nameKey(food.name) }
                    if (same != null) add(same) else add(food.ingredient, food.key)
                }
            }
        }

        if (recipe != null && recipe.id != 0L) {
            val mains = history.filter { it.recipeId == recipe.id && it.sideOf == null }.map { it.id }.toSet()
            history.filter { it.sideOf in mains }.forEach { side ->
                byId[side.ingredientId]?.let { add(it, amount = FoodAmount(side.amount, describe(it, side.amount))) }
            }
        }

        history.filter { it.kind == LogKind.FOOD }.mapNotNull { item -> byId[item.ingredientId]?.takeIf { isCarb(it) } }
            .groupingBy { it.id }.eachCount().entries.sortedByDescending { it.value }
            .forEach { add(byId.getValue(it.key)) }

        return out.values.take(limit)
    }

    /**
     * What to offer with a food that isn't a portion of a recipe, best first and at most [limit]: what was last eaten
     * with it in [history] (newest first), then what usually goes with it ([GOES_WITH]: milk with cereal, butter and
     * jam with toast), matched to one of [ingredients] by name, else to a food of the list.
     */
    fun extrasFor(
        food: Ingredient,
        ingredients: List<Ingredient>,
        history: List<LogItem>,
        limit: Int = 6,
        useFoodList: Boolean = true,
    ): List<SideSuggestion> {
        val live = ingredients.filter { !it.archived }
        val byId = live.associateBy { it.id }
        val out = LinkedHashMap<String, SideSuggestion>()
        fun add(ingredient: Ingredient, foodKey: String? = null, amount: FoodAmount = defaultAmount(ingredient)) {
            if (ingredient.id != 0L && ingredient.id == food.id) return
            if (foodKey == null && ingredient.id == 0L) return
            val key = if (ingredient.id != 0L) "i${ingredient.id}" else "f$foodKey"
            if (key !in out) out[key] = SideSuggestion(ingredient, foodKey, amount)
        }
        if (food.id != 0L) {
            val mains = history.filter { it.ingredientId == food.id && it.sideOf == null }.map { it.id }.toSet()
            history.filter { it.sideOf in mains }.forEach { side ->
                byId[side.ingredientId]?.let { add(it, amount = FoodAmount(side.amount, describe(it, side.amount))) }
            }
        }
        val byKey = live.associateBy { IngredientMatching.nameKey(it.name) }
        for ((pattern, names) in GOES_WITH) {
            if (!pattern.containsMatchIn(food.name)) continue
            for (name in names) {
                // The user's own by name, or a close name that is the same thing ("Lurpak butter", not butter beans).
                val mine = byKey[IngredientMatching.nameKey(name)]
                    ?: IngredientMatching.match(name, live).let { m -> m.ingredientId?.let(byId::get) }
                        ?.takeIf { IngredientMatching.head(it.name) == IngredientMatching.head(name) }
                if (mine != null) {
                    add(mine)
                } else if (useFoodList) {
                    val listed = FoodDatabase.match(name) ?: continue
                    val same = byKey[IngredientMatching.nameKey(listed.name)]
                    if (same != null) add(same) else add(listed.ingredient, listed.key)
                }
            }
        }
        return out.values.take(limit)
    }

    /** "Rice or naan, and a green salad" -> rice, naan, green salad. */
    internal fun servedWithPieces(text: String): List<String> =
        text.split(SPLIT).map { FILLER.replace(it, " ").replace(Regex("""\([^)]*\)"""), " ").trim().trim('.', '!', '-') }
            .map { it.replace(Regex("""\s+"""), " ").removePrefix("a ").removePrefix("an ").trim() }
            .filter { it.length >= 3 }
}
