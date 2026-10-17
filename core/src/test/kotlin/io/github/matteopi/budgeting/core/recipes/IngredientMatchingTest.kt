package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngredientMatchingTest {

    private fun ingredient(id: Long, name: String, archived: Boolean = false) =
        Ingredient(id = id, name = name, archived = archived)

    private val pantry = listOf(
        ingredient(1, "Brown onions"),
        ingredient(2, "Garlic"),
        ingredient(3, "Garlic powder"),
        ingredient(4, "Chestnut mushrooms"),
        ingredient(5, "White (closed cup) mushrooms"),
        ingredient(6, "Olive oil"),
        ingredient(7, "Vegetable oil (rapeseed)"),
        ingredient(8, "Courgette"),
        ingredient(9, "Chicken breast fillets"),
        ingredient(10, "Chopped tomatoes (tin)"),
    )

    private fun match(name: String, aliases: Map<String, Long> = emptyMap(), list: List<Ingredient> = pantry) =
        IngredientMatching.match(name, list, aliases)

    // ---- the starter book -------------------------------------------------------------------

    /** Lines whose name genuinely can't be matched to their ingredient by name alone. None at present. */
    private val knownMisses = emptySet<String>()

    @Test
    fun `every starter line name matches its bundle ingredient among all forty`() {
        val misses = mutableSetOf<String>()
        var checked = 0
        for (recipe in StarterBook.bundle.recipes) {
            for (line in recipe.lines) {
                val key = line.ingredient ?: continue
                val target = StarterBook.ingredient(key)
                val name = IngredientLines.parse(line.text).name
                val result = IngredientMatching.match(name, StarterBook.ingredients)
                checked++
                if (result.best?.ingredientId != target.id) misses += line.text
            }
        }
        assertEquals(knownMisses, misses)
        assertTrue(checked >= 70)
    }

    @Test
    fun `nothing is applied automatically to the wrong ingredient`() {
        var applied = 0
        for (recipe in StarterBook.bundle.recipes) {
            for (line in recipe.lines) {
                val key = line.ingredient ?: continue
                val target = StarterBook.ingredient(key)
                val result = IngredientMatching.match(IngredientLines.parse(line.text).name, StarterBook.ingredients)
                result.ingredientId?.let {
                    applied++
                    assertEquals(target.id, it, "\"${line.text}\" was applied to the wrong ingredient")
                }
            }
        }
        assertTrue(applied > 40, "$applied lines were applied automatically")
    }

    @Test
    fun `lines with no ingredient in the bundle find nothing to apply`() {
        for (text in listOf("4 tbsp water", "400 ml boiling water, for the stock")) {
            val result = IngredientMatching.match(IngredientLines.parse(text).name, StarterBook.ingredients)
            assertNull(result.ingredientId, text)
        }
    }

    // ---- the steps --------------------------------------------------------------------------

    @Test
    fun `a learned alias comes first`() {
        val aliases = mapOf(IngredientMatching.nameKey("spuds") to 8L)
        val m = match("spuds", aliases)
        assertEquals(MatchKind.ALIAS, m.kind)
        assertEquals(8L, m.ingredientId)
        assertEquals(1.0, m.score)
        val over = match("garlic", mapOf(IngredientMatching.nameKey("garlic") to 3L))
        assertEquals(3L, over.ingredientId, "an alias beats an exact name")
    }

    @Test
    fun `an alias for an archived or missing ingredient is ignored`() {
        val withArchived = pantry + ingredient(11, "Spuds", archived = true)
        val m = match("spuds", mapOf(IngredientMatching.nameKey("spuds") to 11L), withArchived)
        assertNull(m.ingredientId)
        assertEquals(MatchKind.NONE, m.kind)
        assertNull(match("spuds", mapOf(IngredientMatching.nameKey("spuds") to 99L)).ingredientId)
    }

    @Test
    fun `an exact name matches once normalised`() {
        assertEquals(2L, match("Garlic").ingredientId)
        assertEquals(MatchKind.EXACT, match("garlic").kind)
        assertEquals(2L, match("garlic cloves").ingredientId)
        assertEquals(2L, match("fresh GARLIC!").ingredientId)
        assertEquals(8L, match("courgettes").ingredientId)
        assertEquals(9L, match("chicken breast fillet").ingredientId)
        assertEquals(10L, match("tins chopped tomatoes").ingredientId)
        assertEquals(5L, match("white mushrooms").ingredientId, "the bracketed words of the ingredient's name may be left out")
        assertEquals(3L, match("Garlic Powder").ingredientId)
    }

    @Test
    fun `descriptive words and the order of words do not matter`() {
        assertEquals(MatchKind.EXACT, match("finely chopped fresh garlic").kind)
        assertEquals(6L, match("oil olive").ingredientId)
        assertEquals(1L, match("large brown onions, diced").ingredientId)
    }

    @Test
    fun `house vocabulary is translated`() {
        assertEquals(7L, match("neutral oil").ingredientId)
        assertEquals(8L, match("zucchini").ingredientId)
    }

    @Test
    fun `overlap offers suggestions with a score and applies only a clear leader`() {
        val onion = match("onion")
        assertEquals(1L, onion.best?.ingredientId)
        assertTrue(onion.score in IngredientMatching.AUTO_APPLY_SCORE..1.0)
        assertEquals(MatchKind.CLOSE, onion.kind)
        assertEquals(1L, onion.ingredientId)

        val powder = match("garlic salt powder")
        assertNull(powder.ingredientId.takeIf { powder.kind == MatchKind.SUGGESTIONS })
        assertEquals(3L, powder.best?.ingredientId)
    }

    @Test
    fun `a tie is not applied automatically`() {
        val m = match("mushrooms")
        assertEquals(MatchKind.SUGGESTIONS, m.kind)
        assertNull(m.ingredientId)
        assertEquals(setOf(4L, 5L), m.suggestions.map { it.ingredientId }.toSet())
        assertEquals(m.suggestions[0].score, m.suggestions[1].score)
        val oil = match("oil")
        assertNull(oil.ingredientId)
        assertEquals(setOf(6L, 7L), oil.suggestions.map { it.ingredientId }.toSet())
    }

    @Test
    fun `suggestions are best first, at most five, and above the minimum score`() {
        val many = (1L..10L).map { ingredient(it, "Red pepper variety $it") }
        val m = IngredientMatching.match("red pepper", many)
        assertTrue(m.suggestions.size <= 5)
        assertTrue(m.suggestions.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertTrue(m.suggestions.all { it.score >= IngredientMatching.MIN_SUGGESTION_SCORE })
    }

    @Test
    fun `no overlap means no match`() {
        val m = match("saffron")
        assertEquals(MatchKind.NONE, m.kind)
        assertNull(m.ingredientId)
        assertTrue(m.suggestions.isEmpty())
        assertEquals(0.0, m.score)
        assertEquals(MatchKind.NONE, match("").kind)
        assertEquals(MatchKind.NONE, match("fresh chopped").kind)
        assertEquals(MatchKind.NONE, IngredientMatching.match("garlic", emptyList()).kind)
    }

    @Test
    fun `archived ingredients are never matched`() {
        val list = listOf(ingredient(1, "Garlic", archived = true), ingredient(2, "Garlic powder"))
        val m = IngredientMatching.match("garlic", list)
        assertTrue(m.suggestions.none { it.ingredientId == 1L })
    }

    @Test
    fun `exact and alias matches list other candidates after the chosen one`() {
        val m = match("garlic")
        assertEquals(2L, m.suggestions.first().ingredientId)
        assertEquals(1.0, m.suggestions.first().score)
        assertTrue(m.suggestions.drop(1).all { it.ingredientId != 2L })
    }

    @Test
    fun `nameKey folds case, accents, plurals and filler words`() {
        assertEquals("garlic", IngredientMatching.nameKey("2 Garlic CLOVES".removePrefix("2 ")))
        assertEquals("tomato puree", IngredientMatching.nameKey("Tomato purée"))
        assertEquals("bird eye chilli", IngredientMatching.nameKey("bird's eye chillies"))
        assertEquals("salt", IngredientMatching.nameKey("Salt only after tasting"))
        assertEquals("thyme mixed herb", IngredientMatching.nameKey("Dried thyme (or mixed herbs)"))
        assertEquals("", IngredientMatching.nameKey("fresh, chopped"))
        assertNotNull(IngredientMatching.nameKey("Olive oil"))
    }
}
