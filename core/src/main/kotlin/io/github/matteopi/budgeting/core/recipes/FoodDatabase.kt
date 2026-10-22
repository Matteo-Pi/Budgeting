package io.github.matteopi.budgeting.core.recipes

import java.time.LocalDate

/**
 * A food of the built-in food list (docs/PLAN.md §11.12): what it becomes as one of the user's ingredients, and the
 * other names it goes by.
 */
data class Food(val key: String, val ingredient: Ingredient, val aliases: List<String>) {
    val name: String get() = ingredient.name

    /** The food as a new ingredient of the user's, added at [nowMillis]. */
    fun toIngredient(nowMillis: Long): Ingredient = ingredient.copy(id = 0, createdAtEpochMillis = nowMillis)
}

/**
 * The built-in food list (docs/PLAN.md §11.12): about 170 everyday foods with a Tesco pack and price and nutrition
 * per 100 g, read once from [RESOURCE]. Nothing of it is stored until the user adds a food as an ingredient.
 */
object FoodDatabase {

    /** The classpath location of the food list, a recipe bundle with no recipes. */
    const val RESOURCE = "/recipes/food-database.json"

    /** When the prices of the foods this list adds were found (the starter foods' were checked 15 to 17 Oct). */
    val PRICES_FOUND: LocalDate = LocalDate.of(2026, 10, 22)

    /** A line with a name's words once the name's bracketed words are left out: as good as the same name. */
    private const val SAME_CORE = 0.99

    /** A same name that also keeps the line's describing words ("chopped"), which settles a tie. */
    private const val SAME_WORDS = 1.01

    /** Taken off a same name for each describing word it has that the line hasn't, so the plainer name wins a tie. */
    private const val EXTRA_WORD = 0.001

    private val BRACKET = Regex("""\([^)]*\)""")

    /** Words that say a line's food comes in a tin. */
    private val TINNED = setOf("tin", "tinned", "can", "canned").map { Quantities.stem(it) }.toSet()

    private class Entry(
        val food: Food,
        val names: List<IngredientMatching.NameTokens>,
        /** Each name's words with its describing words kept and its bracketed ones left out. */
        val words: List<Set<String>>,
        val texts: List<String>,
        val tinned: Boolean,
    )

    val bundle: RecipeBundle by lazy {
        val stream = FoodDatabase::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("The food list is missing from the app.")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        when (val result = RecipeBundle.decode(text)) {
            is BundleDecodeResult.Success -> result.bundle
            is BundleDecodeResult.Failure -> throw IllegalStateException("The food list is invalid: ${result.problems.joinToString(" ")}")
        }
    }

    private val entries: List<Entry> by lazy {
        val models = bundle.toModels().ingredients
        bundle.ingredients.zip(models) { source, model ->
            val food = Food(source.key, model.copy(id = 0), source.aliases)
            val names = listOf(food.name) + food.aliases
            Entry(
                food = food,
                names = names.map { IngredientMatching.nameTokens(it) },
                words = names.map { words(it) },
                texts = names.map { searchText(it) },
                tinned = Quantities.portion(food.ingredient, "tin") != null || "tin" in words(food.name.substringAfter('(', "")),
            )
        }.sortedWith(compareBy { it.food.name.lowercase() })
    }

    /** Every food, by name. */
    fun all(): List<Food> = entries.map { it.food }

    fun byKey(key: String): Food? = entries.firstOrNull { it.food.key == key }?.food

    /**
     * The foods for a search, best first: a name that starts with [text], then another name or a word that does,
     * then any name containing it, then names [IngredientMatching] scores as close; by name within each. Blank
     * [text] gives every food.
     */
    fun search(text: String): List<Food> {
        val query = searchText(text)
        if (query.isEmpty()) return all()
        val line = IngredientMatching.lineTokens(text)
        val lineWords = words(text)
        return entries.mapNotNull { entry ->
            val score = score(line, lineWords, entry)
            val rank = when {
                entry.texts.first().startsWith(query) -> 0
                entry.texts.any { name -> name.split(' ').any { it.startsWith(query) } } -> 1
                entry.texts.any { it.contains(query) } -> 2
                score >= IngredientMatching.MIN_SUGGESTION_SCORE -> 3
                else -> return@mapNotNull null
            }
            Triple(entry.food, rank, score)
        }.sortedWith(compareBy<Triple<Food, Int, Double>> { it.second }.thenByDescending { it.third }.thenBy { it.first.name.lowercase() })
            .map { it.first }
    }

    /**
     * The food for a recipe line's [name] (from `IngredientLines.parse(...).name`), or null: one whose name or other
     * name is the same, or one that [IngredientMatching] would apply automatically, at least
     * [IngredientMatching.AUTO_APPLY_SCORE] and [IngredientMatching.AUTO_APPLY_MARGIN] ahead of the next food.
     *
     * A same name with the line's own describing words wins ("chopped tomatoes" is the tin, "tomatoes" the fresh
     * ones), and a line whose [text] says the food is tinned looks among the tinned foods first.
     */
    fun match(name: String, text: String = name): Food? {
        val line = IngredientMatching.lineTokens(name)
        if (line.isEmpty()) return null
        val lineWords = words(name)
        val scored = entries.map { it to score(line, lineWords, it) }.filter { it.second > 0.0 }
        val tinned = Quantities.words(text).any { Quantities.stem(it) in TINNED }
        return (if (tinned) pick(scored.filter { it.first.tinned }) else null) ?: pick(scored)
    }

    private fun pick(scored: List<Pair<Entry, Double>>): Food? {
        val ranked = scored.sortedWith(compareByDescending<Pair<Entry, Double>> { it.second }.thenBy { it.first.food.name.lowercase() })
        val (best, score) = ranked.firstOrNull() ?: return null
        if (score >= SAME_CORE) return best.food
        val second = ranked.getOrNull(1)?.second ?: 0.0
        return best.food.takeIf { score >= IngredientMatching.AUTO_APPLY_SCORE && score - second >= IngredientMatching.AUTO_APPLY_MARGIN }
    }

    private fun score(line: Set<String>, lineWords: Set<String>, entry: Entry): Double =
        entry.names.indices.maxOf { i ->
            val name = entry.names[i]
            val same = when (line) {
                name.full -> 1.0
                name.core -> SAME_CORE
                else -> return@maxOf IngredientMatching.nameScore(line, name)
            }
            val words = entry.words[i]
            if (words == lineWords) SAME_WORDS else same - EXTRA_WORD * words.count { it !in lineWords }
        }

    /** A name's words as stems, describing words kept, bracketed, filler and container words left out. */
    internal fun words(text: String): Set<String> =
        Quantities.words(BRACKET.replace(text, " ")).filter { !NameWords.isStopWord(it) && !NameWords.isContainer(it) }
            .map { Quantities.stem(it) }.toSet()

    /** Lower case, no accents, words separated by single spaces: "Crème fraîche" -> "creme fraiche". */
    private fun searchText(text: String): String = Quantities.words(text).joinToString(" ")
}
