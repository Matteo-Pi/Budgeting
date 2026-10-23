package io.github.matteopi.budgeting.core.nutrition

import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientLines
import io.github.matteopi.budgeting.core.recipes.IngredientMatching
import io.github.matteopi.budgeting.core.recipes.MatchKind
import io.github.matteopi.budgeting.core.recipes.ParsedLine
import io.github.matteopi.budgeting.core.recipes.Quantities

/** What [QuickFood.read] found: the food (an ingredient, or a food of the list with [foodKey]) and how much. */
data class QuickFoodResult(
    val ingredient: Ingredient,
    val foodKey: String?,
    val amount: FoodAmount,
    /** Whether the text said how much (else [amount] is the default). */
    val amountGiven: Boolean,
)

/** One food of a quick entry ("toast" of "2 slices of toast with butter"), with the foods it could be, best first. */
data class QuickPart(val text: String, val options: List<QuickFoodResult>)

/** Reading "3 slices of toast" or "200 g yoghurt" into a food and an amount (docs/PLAN.md §14.4). */
object QuickFood {

    /** Words people eat that are another food's name. */
    private val SAME_AS = mapOf(
        "toast" to "white bread",
        "white toast" to "white bread",
        "brown toast" to "wholemeal bread",
        "wholemeal toast" to "wholemeal bread",
        "seeded toast" to "seeded bread",
        "sourdough toast" to "sourdough bread",
        "cuppa" to "milk",
        "egg" to "eggs",
        "boiled egg" to "eggs",
        "fried egg" to "eggs",
        "poached egg" to "eggs",
        "scrambled eggs" to "eggs",
        "chips" to "oven chips",
        "porridge" to "porridge oats",
        "cereal" to "cornflakes",
        "jacket potato" to "baking potatoes",
        "baked potato" to "baking potatoes",
        "rice" to "long-grain white rice",
    )

    /** "slices of toast", "a bowl of porridge": the leading portion word and what it is of. */
    private val PORTION_OF = Regex("""^(\S+)\s+of\s+(.+)$""", RegexOption.IGNORE_CASE)

    /** Portion words that can lead a name without "of": "2 slices toast". */
    private val PORTION_WORDS = setOf("slice", "piece", "bowl", "cup", "glass", "handful", "pot", "bar", "can", "tin", "pack", "packet", "bag", "portion", "serving", "rasher", "fillet")
        .map { Quantities.stem(it) }.toSet()

    private val SPOONS = setOf("tsp", "tbsp", "pinch", "spray").map { Quantities.stem(it) }.toSet()

    /** The least score of a close name taken without asking from the user's own ingredients. */
    private const val MIN_SCORE = 0.5

    /**
     * Reads [text]: the amount and name as a recipe line is read, a leading "slices of" kept as the portion, a few
     * everyday names turned into the food's ("toast" is bread), then the food: one of [ingredients] by name or
     * [aliases], else a food of the list. The amount is the text's, in the food's portion when it names one
     * ("3 slices"), else its `each` portion, else the food's default amount ([Sides.defaultAmount]). Null when
     * nothing fits.
     */
    fun read(text: String, ingredients: List<Ingredient>, aliases: Map<String, Long> = emptyMap(), useFoodList: Boolean = true): QuickFoodResult? {
        val q = parse(text) ?: return null
        val live = ingredients.filter { !it.archived }
        val match = IngredientMatching.match(q.lookup, live, aliases, q.unit)
        val mine = when {
            match.ingredientId != null -> live.firstOrNull { it.id == match.ingredientId }
            match.kind == MatchKind.SUGGESTIONS && (match.best?.score ?: 0.0) >= MIN_SCORE ->
                // Only a close name that is the same kind of thing: "white bread" is not white mushrooms.
                live.firstOrNull { it.id == match.best?.ingredientId }?.takeIf { sameKind(it, q.lookup) }
            else -> null
        }
        val (ingredient, foodKey) = when {
            mine != null -> mine to null
            useFoodList -> {
                val food = FoodDatabase.match(q.lookup, text) ?: FoodDatabase.search(q.lookup).firstOrNull() ?: return null
                val same = live.firstOrNull { IngredientMatching.nameKey(it.name) == IngredientMatching.nameKey(food.name) }
                if (same != null) same to null else food.ingredient to food.key
            }
            else -> return null
        }
        return q.result(ingredient, foodKey)
    }

    /**
     * Several foods [text] could mean, best first and at most [limit], each with the text's amount: what [read] picks,
     * then the user's ingredients whose names are close (the same kind of thing), then the food list's search for it.
     * A food of the list that one of [ingredients] already is (by name) is offered as that ingredient, once.
     */
    fun options(
        text: String,
        ingredients: List<Ingredient>,
        aliases: Map<String, Long> = emptyMap(),
        limit: Int = 8,
        useFoodList: Boolean = true,
    ): List<QuickFoodResult> {
        val q = parse(text) ?: return emptyList()
        val live = ingredients.filter { !it.archived }
        val byKey = live.associateBy { IngredientMatching.nameKey(it.name) }
        val out = LinkedHashMap<String, QuickFoodResult>()
        fun add(ingredient: Ingredient, foodKey: String?) {
            val mine = if (foodKey != null) byKey[IngredientMatching.nameKey(ingredient.name)] else null
            val chosen = mine ?: ingredient
            val key = if (mine != null || foodKey == null) "i${chosen.id}" else "f$foodKey"
            if (key !in out) out[key] = q.result(chosen, if (mine != null) null else foodKey)
        }
        read(text, live, aliases, useFoodList)?.let { add(it.ingredient, it.foodKey) }
        val match = IngredientMatching.match(q.lookup, live, aliases, q.unit)
        match.suggestions.filter { it.score >= MIN_SCORE }.forEach { suggestion ->
            live.firstOrNull { it.id == suggestion.ingredientId }?.takeIf { sameKind(it, q.lookup) }?.let { add(it, null) }
        }
        val words = Quantities.words(q.lookup).toSet()
        live.filter { ingredient -> Quantities.words(ingredient.name).any { it in words } && sameKind(ingredient, q.lookup) }
            .sortedBy { it.name.lowercase() }
            .forEach { add(it, null) }
        if (useFoodList) {
            // Foods that are the same kind of thing first ("bread": white, wholemeal, seeded… before breadcrumbs).
            val found = (FoodDatabase.search(q.lookup).take(limit * 4) + FoodDatabase.search(q.name).take(limit * 4)).distinctBy { it.key }
            (found.filter { sameKind(it.ingredient, q.lookup) || sameKind(it.ingredient, q.name) } + found)
                .forEach { add(it.ingredient, it.key) }
        }
        return out.values.take(limit)
    }

    /** What splits a quick entry into foods: "with", "and", "plus", commas, "+" and "&". */
    private val SPLIT = Regex("""\s*(?:,|\+|&|\bwith\b|\band\b|\bplus\b|\bon\b)\s*""", RegexOption.IGNORE_CASE)

    /**
     * Each food of [text] with its [options]: "cereal with milk" is cereal and milk, "2 slices of toast with butter
     * and jam" toast, butter and jam. The whole text stays one food when it is a food's name ("salt and vinegar
     * crisps") or when splitting leaves only one part. Parts nothing fits are left out.
     */
    fun readAll(
        text: String,
        ingredients: List<Ingredient>,
        aliases: Map<String, Long> = emptyMap(),
        limit: Int = 5,
        useFoodList: Boolean = true,
    ): List<QuickPart> {
        val whole = text.trim()
        if (whole.isEmpty()) return emptyList()
        val pieces = whole.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val keepWhole = pieces.size <= 1 || isFoodName(whole, ingredients, useFoodList)
        val parts = if (keepWhole) listOf(whole) else pieces
        return parts.mapNotNull { part ->
            options(part, ingredients, aliases, limit, useFoodList).takeIf { it.isNotEmpty() }?.let { QuickPart(part, it) }
        }
    }

    /** True when [text] (its amount aside) is the name of one of [ingredients] or of a food of the list. */
    private fun isFoodName(text: String, ingredients: List<Ingredient>, useFoodList: Boolean): Boolean {
        val q = parse(text) ?: return false
        val key = IngredientMatching.nameKey(q.lookup)
        if (ingredients.any { !it.archived && IngredientMatching.nameKey(it.name) == key }) return true
        if (!useFoodList) return false
        val food = FoodDatabase.match(q.lookup, text) ?: return false
        return IngredientMatching.nameKey(food.name).split(' ').containsAll(key.split(' '))
    }

    /** True when [ingredient]'s last word is a word of [lookup]: "white bread" for "Hovis white bread", not "White mushrooms". */
    private fun sameKind(ingredient: Ingredient, lookup: String): Boolean =
        IngredientMatching.head(ingredient.name) in IngredientMatching.lineTokens(lookup)

    /** The text read apart: the line, the portion word it led with, the name to look up and the line's unit. */
    private class Parsed(val parsed: ParsedLine, val portionWord: String?, val name: String, val lookup: String, val unit: io.github.matteopi.budgeting.core.recipes.BaseUnit?) {
        fun result(ingredient: Ingredient, foodKey: String?): QuickFoodResult {
            val amount = amountOf(parsed, portionWord, ingredient)
            return QuickFoodResult(ingredient, foodKey, amount ?: Sides.defaultAmount(ingredient), amount != null)
        }
    }

    private fun parse(text: String): Parsed? {
        val parsed = IngredientLines.parse(text.trim())
        var name = parsed.name.trim()
        var portionWord: String? = null
        PORTION_OF.matchEntire(name)?.let {
            portionWord = it.groupValues[1]
            name = it.groupValues[2]
        }
        if (portionWord == null) {
            val words = name.split(Regex("""\s+"""))
            if (words.size > 1 && Quantities.stem(words.first().lowercase()) in PORTION_WORDS) {
                portionWord = words.first()
                name = words.drop(1).joinToString(" ")
            }
        }
        if (name.isBlank()) return null
        val lookup = SAME_AS[Quantities.words(name).joinToString(" ")] ?: name
        return Parsed(parsed, portionWord, name, lookup, parsed.measure?.let { Quantities.unitOf(it) })
    }

    private fun amountOf(parsed: ParsedLine, portionWord: String?, ingredient: Ingredient): FoodAmount? {
        val count = parsed.amount ?: return null
        val words = listOfNotNull(portionWord?.lowercase()) + parsed.nameWords
        val resolved = IngredientLines.resolve(parsed.copy(nameWords = words), ingredient)?.quantity
            ?: if (parsed.measure == null) {
                ingredient.portions.firstOrNull { Quantities.stem(it.name) !in SPOONS }?.let { count * it.amount }
            } else {
                null
            }
        return resolved?.takeIf { it > 0 }?.let { FoodAmount(it, Sides.describe(ingredient, it)) }
    }
}
