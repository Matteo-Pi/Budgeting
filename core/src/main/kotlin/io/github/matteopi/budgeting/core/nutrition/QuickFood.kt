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

        val live = ingredients.filter { !it.archived }
        val unit = parsed.measure?.let { Quantities.unitOf(it) }
        val match = IngredientMatching.match(lookup, live, aliases, unit)
        val mine = when {
            match.ingredientId != null -> live.firstOrNull { it.id == match.ingredientId }
            match.kind == MatchKind.SUGGESTIONS && (match.best?.score ?: 0.0) >= MIN_SCORE ->
                // Only a close name that is the same kind of thing: "white bread" is not white mushrooms.
                live.firstOrNull { it.id == match.best?.ingredientId }?.takeIf { IngredientMatching.head(it.name) in IngredientMatching.lineTokens(lookup) }
            else -> null
        }
        val (ingredient, foodKey) = when {
            mine != null -> mine to null
            useFoodList -> {
                val food = FoodDatabase.match(lookup, text) ?: FoodDatabase.search(lookup).firstOrNull() ?: return null
                val same = live.firstOrNull { IngredientMatching.nameKey(it.name) == IngredientMatching.nameKey(food.name) }
                if (same != null) same to null else food.ingredient to food.key
            }
            else -> return null
        }

        val amount = amountOf(parsed, portionWord, ingredient)
        return QuickFoodResult(ingredient, foodKey, amount ?: Sides.defaultAmount(ingredient), amount != null)
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
