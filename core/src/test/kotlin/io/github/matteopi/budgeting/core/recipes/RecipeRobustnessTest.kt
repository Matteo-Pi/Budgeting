package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recipe text comes from people and from the clipboard: nothing here may throw, whatever is pasted. */
class RecipeRobustnessTest {

    private val fragments = listOf(
        "1", "2", "10", "400", "0", "00", "99999999999999999999", "1.5", ".", "..", "½", "¼", "¾", "⅓", "/", "1/2", "1/0", "-", "–", "to",
        "×", "x", "X", "g", "kg", "ml", "l", "tsp", "tbsp", "pinch", "oz", "cup", "of", "a", "an", "half", "(", ")", "(about 320 g)",
        "(optional)", ",", ";", ":", " ", "  ", "\t", "\n", "\n\n", "#", "##", "###", "- ", "* ", "• ", "1. ", "2) ", "Step 3: ",
        "Ingredients", "Method", "Tips", "Equipment", "Storage", "Serves", "Serves: 4", "Prep:", "Cook: 1h30", "Style:", "Tags:",
        "About:", "Serve with:", "Time:", "·", "|", "Ready when:", "If it burns:", "onion", "garlic cloves", "chicken breast fillets",
        "salt", "optional", "é", "ß", "漢字", "😀", "\u0000", "\r\n", "**", "__",
    )

    private fun randomText(random: Random, parts: Int): String =
        (1..parts).joinToString("") { fragments[random.nextInt(fragments.size)] + if (random.nextInt(3) == 0) " " else "" }

    private val onion = Ingredient(
        id = 1, name = "Onion", packSize = 1000.0, price = Money(100),
        portions = listOf(Portion("each", 150.0), Portion("clove", 5.0), Portion("tin", 240.0)),
    )

    @Test
    fun `reading, resolving and scaling a line never throws`() {
        val random = Random(20261017)
        repeat(5000) {
            val text = randomText(random, 1 + random.nextInt(10))
            val parsed = IngredientLines.parse(text)
            IngredientLines.resolve(parsed, onion)
            IngredientLines.resolve(parsed, onion.copy(unit = BaseUnit.ML, portions = emptyList()))
            for (factor in listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 7.0)) {
                IngredientLines.scaleText(text, factor, 100.0, BaseUnit.G)
                IngredientLines.scaleText(text, factor)
            }
            IngredientMatching.match(parsed.name, listOf(onion))
            IngredientMatching.nameKey(text)
        }
    }

    @Test
    fun `reading, formatting and re-reading a pasted recipe never throws`() {
        val random = Random(1017)
        repeat(3000) {
            val text = randomText(random, 5 + random.nextInt(40))
            val draft = RecipeText.parse(text)
            val again = RecipeText.parse(RecipeText.format(draft))
            RecipeText.format(again)
            RecipeText.looksLikeRecipe(text)
            for (step in draft.steps) RecipeSteps.paragraphs(step.body)
        }
    }

    @Test
    fun `what is read from messy text is stable once formatted`() {
        val random = Random(77)
        var stable = 0
        repeat(1000) {
            val draft = RecipeText.parse(randomText(random, 5 + random.nextInt(40)))
            val once = RecipeText.format(draft)
            val twice = RecipeText.format(RecipeText.parse(once))
            if (once == twice) stable++
        }
        assertTrue(stable > 900, "$stable of 1000 messy pastes format to the same text the second time")
    }

    @Test
    fun `scaling a starter recipe to any servings keeps a portion the same`() {
        for (key in StarterBook.models.recipeKeys.keys) {
            val own = StarterBook.numbers(key)
            for (servings in 1..12) {
                val n = StarterBook.numbers(key, servings = servings)
                assertEquals(own.cost.perPortion, n.cost.perPortion, "$key at $servings")
                assertEquals(own.nutrition.perPortion.kcal, n.nutrition.perPortion.kcal, 1e-6, "$key at $servings")
                assertEquals(own.veg, n.veg, 1e-9, "$key at $servings")
                assertTrue(n.cost.fromEmptyCupboard.pence >= n.cost.whole.pence - 1, "$key at $servings: whole packs cost at least what is used")
            }
        }
    }

    @Test
    fun `a grade such as 00 is part of the name, not an amount`() {
        val p = IngredientLines.parse("500 g 00 flour")
        assertEquals(500.0, p.amount)
        assertEquals("00 flour", p.name)
        val bare = IngredientLines.parse("00 flour")
        assertNull(bare.amount)
        assertEquals("00 flour", bare.name)
        assertEquals(0.5, IngredientLines.parse("0.5 tsp salt").amount)
    }
}
