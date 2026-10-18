package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Veg with and without the pulses cap, and added sugars (docs/PLAN.md §11.11). */
class VegAndSugarsTest {

    private fun ing(id: Long, veg: VegKind = VegKind.NONE, shelf: Shelf = Shelf.OTHER, per100: Nutrition = Nutrition()) =
        Ingredient(id = id, name = "Ingredient $id", packSize = 100.0, price = Money(100), vegKind = veg, shelf = shelf, per100 = per100)

    private fun line(id: Long, quantity: Double, optional: Boolean = false) =
        RecipeLine(text = "x", ingredientId = id, quantity = quantity, optional = optional)

    private fun analyse(recipe: Recipe, ingredients: List<Ingredient>, countOptional: Boolean = true) =
        RecipeMaths.analyse(recipe, ingredients.associateBy { it.id }, emptyMap(), recipe.serves, countOptional)

    @Test
    fun `the chilli has more veg in total than counts towards 5 a day`() {
        val veg = StarterBook.numbers("chilli").vegSummary
        assertEquals(6.6875, veg.total, 1e-9)
        assertEquals(5.875, veg.effective, 1e-9)
        assertEquals(StarterBook.numbers("chilli").veg, veg.effective, 1e-12)
        assertEquals(4.875, veg.veg, 1e-9)
        assertEquals(1.8125, veg.pulses, 1e-9)
        assertEquals(1.0, veg.pulsesCounted, 1e-12)
        assertTrue(veg.capped)
        val names = veg.items.map { StarterBook.ingredientById.getValue(it.ingredientId).name }
        assertEquals(
            listOf(
                "Chopped tomatoes (tin)", "Red kidney beans (tin, drained)", "Brown onions", "Pepper (red, yellow or green)",
                "Carrots", "Red split lentils (dried)",
            ),
            names,
            "largest first",
        )
        assertEquals(2.5, veg.items.first().portions, 1e-9)
    }

    @Test
    fun `without pulses the two numbers are the same`() {
        for (key in listOf("stir-fry", "traybake", "braise")) {
            val veg = StarterBook.numbers(key).vegSummary
            assertEquals(veg.total, veg.effective, 1e-12, key)
            assertFalse(veg.capped, key)
            assertEquals(0.0, veg.pulses, key)
        }
        assertEquals(6.0625, StarterBook.numbers("stir-fry").vegSummary.total, 1e-9)
    }

    @Test
    fun `pulses under one portion are counted in full and items follow the servings`() {
        val beans = ing(1, VegKind.PULSE)
        val carrot = ing(2, VegKind.VEG)
        val recipe = Recipe(id = 1, title = "T", serves = 2, lines = listOf(line(1, 120.0), line(2, 320.0), line(2, 80.0, optional = true)))
        val veg = analyse(recipe, listOf(beans, carrot)).vegSummary
        assertEquals(0.75, veg.pulses, 1e-12)
        assertEquals(0.75, veg.pulsesCounted, 1e-12)
        assertEquals(3.25, veg.total, 1e-12)
        assertEquals(veg.total, veg.effective, 1e-12)
        assertEquals(listOf(VegItem(2, VegKind.VEG, 2.5), VegItem(1, VegKind.PULSE, 0.75)), veg.items)
        val noExtras = analyse(recipe, listOf(beans, carrot), countOptional = false).vegSummary
        assertEquals(2.75, noExtras.total, 1e-12)
    }

    @Test
    fun `added sugars per portion for the starter recipes`() {
        assertEquals(7.534, StarterBook.numbers("stir-fry").nutrition.perPortion.addedSugars, 1e-9)
        assertEquals(5.884, StarterBook.numbers("stir-fry", countOptional = false).nutrition.perPortion.addedSugars, 1e-9)
        assertEquals(0.0, StarterBook.numbers("traybake").nutrition.perPortion.addedSugars, 1e-12)
        assertEquals(0.193, StarterBook.numbers("braise").nutrition.perPortion.addedSugars, 1e-9)
        assertEquals(0.075, StarterBook.numbers("chilli").nutrition.perPortion.addedSugars, 1e-9)
        for (key in StarterBook.models.recipeKeys.keys) {
            val n = StarterBook.numbers(key).nutrition
            assertTrue(n.addedSugarsUnknown.isEmpty(), key)
            assertTrue(n.perPortion.addedSugars <= n.perPortion.sugars, "$key: added sugars are part of the sugars")
        }
        assertEquals("7.5", RecipeMaths.formatNutrient(Nutrient.ADDED_SUGARS, 7.534))
        assertEquals(25.1, StarterBook.numbers("stir-fry").nutrition.percentRi(Nutrient.ADDED_SUGARS), 0.05, "of the 30 g a day limit")
    }

    @Test
    fun `unknown added sugars count as none for fresh produce and sugar-free foods, else only that row is at least`() {
        val sauce = ing(1, per100 = Nutrition(kcal = 100.0, fat = 0.0, saturates = 0.0, carbs = 20.0, sugars = 18.0, fibre = 0.0, protein = 1.0, salt = 2.0))
        val tomato = ing(2, shelf = Shelf.FRUIT_VEG, per100 = Nutrition(kcal = 20.0, fat = 0.0, saturates = 0.0, carbs = 3.0, sugars = 3.0, fibre = 1.0, protein = 1.0, salt = 0.0))
        val chicken = ing(3, shelf = Shelf.MEAT, per100 = Nutrition(kcal = 120.0, fat = 2.0, saturates = 0.5, carbs = 0.0, sugars = 0.0, fibre = 0.0, protein = 25.0, salt = 0.2))
        val honey = ing(4, per100 = Nutrition(kcal = 300.0, sugars = 80.0, addedSugars = 80.0))
        assertNull(RecipeMaths.addedSugarsPer100(sauce))
        assertEquals(0.0, RecipeMaths.addedSugarsPer100(tomato))
        assertEquals(0.0, RecipeMaths.addedSugarsPer100(chicken))
        assertEquals(80.0, RecipeMaths.addedSugarsPer100(honey))

        val recipe = Recipe(id = 1, title = "T", serves = 1, lines = listOf(line(1, 50.0), line(2, 100.0), line(3, 200.0), line(4, 10.0)))
        val n = analyse(recipe, listOf(sauce, tomato, chicken, honey)).nutrition
        assertEquals(8.0, n.perPortion.addedSugars, 1e-9, "only the honey's")
        assertEquals(listOf(1L), n.addedSugarsUnknown)
        assertTrue(Nutrient.ADDED_SUGARS in n.unknownNutrients)
        assertEquals(listOf(4L), n.unknownIngredients, "the sauce knows its label values; the honey doesn't")
    }

    @Test
    fun `every starter ingredient has its added sugars written out`() {
        for (ingredient in StarterBook.ingredients) {
            val added = ingredient.per100.addedSugars
            assertTrue(added != null, ingredient.name)
            assertTrue(added <= (ingredient.per100.sugars ?: 0.0) + 1e-9, "${ingredient.name}: added sugars are at most the sugars")
        }
        assertEquals(82.4, StarterBook.ingredient("honey").per100.addedSugars)
        assertEquals(0.0, StarterBook.ingredient("tomato_puree").per100.addedSugars)
    }
}
