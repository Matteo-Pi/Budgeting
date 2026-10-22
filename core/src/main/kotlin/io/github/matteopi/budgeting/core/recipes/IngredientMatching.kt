package io.github.matteopi.budgeting.core.recipes

/** Words that don't help tell one ingredient from another. All compared by [Quantities.stem]. */
internal object NameWords {
    private val DESCRIPTORS = stems(
        "fresh", "large", "small", "medium", "big", "finely", "roughly", "coarsely", "thinly", "thickly",
        "freshly", "chopped", "diced", "sliced", "minced", "grated", "crushed", "peeled", "ripe",
        "boneless", "skinless", "dried",
    )
    private val STOP = stems(
        "of", "or", "and", "a", "an", "the", "with", "to", "for", "in", "per", "any", "some", "plus",
        "only", "after", "before", "taste", "tasting", "serving", "garnish", "finish", "extra", "optional",
    )
    private val CONTAINERS = stems(
        "clove", "head", "tin", "can", "pack", "packet", "jar", "bottle", "carton", "tub", "bag", "bunch",
        "cube", "rasher", "sprig", "stick", "slice", "piece", "handful", "knob", "sachet", "tube", "stalk",
        "leaf", "fillet", "pot", "punnet", "tray", "block",
    )

    private fun stems(vararg words: String): Set<String> = words.map { Quantities.stem(it) }.toSet()

    fun isDescriptor(word: String) = Quantities.stem(word) in DESCRIPTORS
    fun isStopWord(word: String) = Quantities.stem(word) in STOP
    fun isContainer(word: String) = Quantities.stem(word) in CONTAINERS
}

/** How a line's name was matched to an ingredient. */
enum class MatchKind {
    /** A learned alias for exactly this name. */
    ALIAS,

    /** The same name once both are normalised. */
    EXACT,

    /** Not the same name, but close enough and clearly ahead of the rest: applied automatically. */
    CLOSE,

    /** Only suggestions; nothing is applied until the user chooses. */
    SUGGESTIONS,

    NONE,
}

/** An ingredient that might be the one meant, with how well its name fits (1.0 = the same). */
data class MatchSuggestion(val ingredientId: Long, val score: Double)

/**
 * The outcome of [IngredientMatching.match]. [ingredientId] is the ingredient to use without asking
 * (for [MatchKind.ALIAS], [MatchKind.EXACT] and [MatchKind.CLOSE]); it is null when the user has to
 * choose, in which case [suggestions] (best first) are what to offer.
 */
data class IngredientMatch(
    val kind: MatchKind,
    val ingredientId: Long?,
    val score: Double,
    val suggestions: List<MatchSuggestion>,
) {
    val best: MatchSuggestion? get() = suggestions.firstOrNull()
}

/** Matching the name on a recipe line to an ingredient (docs/PLAN.md §11.4). */
object IngredientMatching {

    /** A fuzzy match at or above this score, and clearly ahead of the next, is applied automatically. */
    const val AUTO_APPLY_SCORE = 0.8

    /** The best match must lead the second best by at least this much to be applied automatically. */
    const val AUTO_APPLY_MARGIN = 0.1

    /** Suggestions below this score are not offered. */
    const val MIN_SUGGESTION_SCORE = 0.4

    private const val MAX_SUGGESTIONS = 5

    private val BRACKET = Regex("""\([^()]*\)""")

    private val SYNONYMS: List<Pair<List<String>, List<String>>> = listOf(
        "neutral oil" to "vegetable oil",
        "cooking oil" to "vegetable oil",
        "zucchini" to "courgette",
        "eggplant" to "aubergine",
        "cilantro" to "coriander",
        "scallion" to "spring onion",
        "arugula" to "rocket",
        "cornstarch" to "cornflour",
        "garbanzo" to "chickpea",
        "capsicum" to "pepper",
        "bell pepper" to "pepper",
        "ground beef" to "beef mince",
        "minced beef" to "beef mince",
        "tomato paste" to "tomato puree",
        "heavy cream" to "double cream",
        "confectioners sugar" to "icing sugar",
    ).map { (from, to) -> Quantities.words(from).map { Quantities.stem(it) } to Quantities.words(to).map { Quantities.stem(it) } }
        .sortedByDescending { it.first.size }

    private fun tokens(text: String, withBrackets: Boolean): List<String> {
        val source = if (withBrackets) text else BRACKET.replace(text, " ")
        val stems = Quantities.words(source).map { Quantities.stem(it) }
        val replaced = ArrayList<String>(stems.size)
        var i = 0
        while (i < stems.size) {
            val synonym = SYNONYMS.firstOrNull { (from, _) ->
                from.size <= stems.size - i && stems.subList(i, i + from.size) == from
            }
            if (synonym != null) {
                replaced += synonym.second
                i += synonym.first.size
            } else {
                replaced += stems[i]
                i++
            }
        }
        return replaced.filter { !NameWords.isStopWord(it) && !NameWords.isDescriptor(it) && !NameWords.isContainer(it) }
    }

    /**
     * The key a name is compared and learned under: lower case, no accents or punctuation, simple
     * plurals folded, and words such as "fresh", "chopped", "clove" and "of" left out. "2 garlic
     * cloves" and "Garlic" share the key "garlic".
     */
    fun nameKey(name: String): String = tokens(name, withBrackets = true).joinToString(" ")

    /** A name's words as [match] compares them: with its bracketed words ([full]) and without them ([core]). */
    internal class NameTokens(val core: Set<String>, val full: Set<String>)

    internal fun nameTokens(name: String): NameTokens {
        val full = tokens(name, withBrackets = true).toSet()
        return NameTokens(tokens(name, withBrackets = false).toSet().ifEmpty { full }, full)
    }

    /** A recipe line's name as [match] compares it. */
    internal fun lineTokens(name: String): Set<String> = tokens(name, withBrackets = true).toSet()

    /**
     * How well a line fits a name, 0 to 1, as [match] scores it: 1.0 for the same words (the name's bracketed words
     * may be left out), else the word overlap.
     */
    internal fun nameScore(line: Set<String>, name: NameTokens): Double = when {
        line.isEmpty() -> 0.0
        line == name.core || line == name.full -> 1.0
        else -> maxOf(score(line, name.core), score(line, name.full))
    }

    private class Candidate(val ingredient: Ingredient, val core: Set<String>, val full: Set<String>)

    private fun candidates(ingredients: List<Ingredient>): List<Candidate> =
        ingredients.filter { !it.archived }.map { ingredient ->
            val full = tokens(ingredient.name, withBrackets = true).toSet()
            val core = tokens(ingredient.name, withBrackets = false).toSet().ifEmpty { full }
            Candidate(ingredient, core, full)
        }

    private fun score(line: Set<String>, other: Set<String>): Double {
        if (line.isEmpty() || other.isEmpty()) return 0.0
        val shared = line.count { it in other }
        if (shared == 0) return 0.0
        val dice = 2.0 * shared / (line.size + other.size)
        val coverage = shared.toDouble() / line.size
        return (dice + coverage) / 2
    }

    /**
     * Finds the ingredient for a line's [name] (from `IngredientLines.parse(...).name`).
     *
     * 1. A learned alias: [aliases] maps a [nameKey] to an ingredient id.
     * 2. An exact name: the same words once both are normalised (bracketed words of the ingredient's
     *    name may be left out: "White (closed cup) mushrooms" is "white mushrooms").
     * 3. Word overlap, scored 0 to 1. The best is applied automatically only from [AUTO_APPLY_SCORE]
     *    and when it is [AUTO_APPLY_MARGIN] ahead of the next; otherwise the top few are offered as
     *    suggestions.
     *
     * Archived ingredients are never matched.
     */
    fun match(name: String, ingredients: List<Ingredient>, aliases: Map<String, Long> = emptyMap()): IngredientMatch {
        val lineTokens = tokens(name, withBrackets = true)
        val line = lineTokens.toSet()
        if (line.isEmpty()) return IngredientMatch(MatchKind.NONE, null, 0.0, emptyList())
        val all = candidates(ingredients)

        val aliasId = aliases[lineTokens.joinToString(" ")]
        if (aliasId != null && all.any { it.ingredient.id == aliasId }) {
            return withOthers(MatchKind.ALIAS, aliasId, line, all)
        }
        val exact = all.firstOrNull { it.core == line || it.full == line }
        if (exact != null) return withOthers(MatchKind.EXACT, exact.ingredient.id, line, all)

        val scored = scoredSuggestions(line, all)
        val best = scored.firstOrNull() ?: return IngredientMatch(MatchKind.NONE, null, 0.0, emptyList())
        val second = scored.getOrNull(1)
        val clear = best.score >= AUTO_APPLY_SCORE && (second == null || best.score - second.score >= AUTO_APPLY_MARGIN)
        return if (clear) {
            IngredientMatch(MatchKind.CLOSE, best.ingredientId, best.score, scored)
        } else {
            IngredientMatch(MatchKind.SUGGESTIONS, null, best.score, scored)
        }
    }

    private fun scoredSuggestions(line: Set<String>, all: List<Candidate>): List<MatchSuggestion> =
        all.map { MatchSuggestion(it.ingredient.id, maxOf(score(line, it.core), score(line, it.full))) to it.ingredient.name }
            .filter { it.first.score >= MIN_SUGGESTION_SCORE }
            .sortedWith(compareByDescending<Pair<MatchSuggestion, String>> { it.first.score }.thenBy { it.second.lowercase() })
            .map { it.first }
            .take(MAX_SUGGESTIONS)

    private fun withOthers(kind: MatchKind, id: Long, line: Set<String>, all: List<Candidate>): IngredientMatch {
        val others = scoredSuggestions(line, all.filter { it.ingredient.id != id }).take(MAX_SUGGESTIONS - 1)
        return IngredientMatch(kind, id, 1.0, listOf(MatchSuggestion(id, 1.0)) + others)
    }
}
