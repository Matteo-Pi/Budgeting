package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeModelsTest {

    @Test
    fun `shelves are declared in shop order`() {
        assertEquals(
            listOf("FRUIT_VEG", "MEAT", "DAIRY", "FROZEN", "CUPBOARD", "OILS", "HERBS_SPICES", "OTHER"),
            Shelf.entries.map { it.name },
        )
    }

    @Test
    fun `the enums have the values the database stores`() {
        assertEquals(listOf("G", "ML"), BaseUnit.entries.map { it.name })
        assertEquals(listOf("NONE", "VEG", "PULSE"), VegKind.entries.map { it.name })
        assertEquals(listOf("LABEL", "BARCODE", "TYPED", "ESTIMATE"), NutritionSource.entries.map { it.name })
        assertEquals(listOf("MINE", "CLAUDE", "IMPORTED"), RecipeSource.entries.map { it.name })
        assertEquals(listOf("STEP", "TIP", "EQUIPMENT", "STORAGE"), StepKind.entries.map { it.name })
        assertEquals(9, Nutrient.entries.size)
        assertEquals(Nutrient.entries - Nutrient.ADDED_SUGARS, Nutrient.LABEL)
        assertEquals("NONE", FoodGroup.entries.last().name)
    }

    @Test
    fun `nutrition values are looked up by nutrient and unknown is not zero`() {
        val n = Nutrition(kcal = 100.0, protein = 0.0)
        assertEquals(100.0, n[Nutrient.KCAL])
        assertEquals(0.0, n[Nutrient.PROTEIN])
        assertNull(n[Nutrient.SALT])
        assertTrue(n.hasUnknown)
        assertFalse(n.isEmpty)
        assertTrue(Nutrition().isEmpty)
        assertFalse(Nutrition(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0).hasUnknown)
    }

    @Test
    fun `a recipe splits its steps by kind and adds up its time`() {
        val r = Recipe(
            title = "T", prepMins = 10, cookMins = 20,
            steps = listOf(
                RecipeStep(body = "a"), RecipeStep(StepKind.TIP, body = "b"), RecipeStep(body = "c"),
                RecipeStep(StepKind.EQUIPMENT, body = "d"), RecipeStep(StepKind.STORAGE, body = "e"),
            ),
        )
        assertEquals(listOf("a", "c"), r.method.map { it.body })
        assertEquals(listOf("b"), r.tips.map { it.body })
        assertEquals(listOf("d"), r.equipment.map { it.body })
        assertEquals(listOf("e"), r.storage.map { it.body })
        assertEquals(30, r.totalMins)
        assertEquals(10, Recipe(title = "T", prepMins = 10).totalMins)
        assertEquals(20, Recipe(title = "T", cookMins = 20).totalMins)
        assertNull(Recipe(title = "T").totalMins)
    }

    @Test
    fun `optional fields default so a new row is cheap to build`() {
        val i = Ingredient(name = "Salt")
        assertEquals(0L, i.id)
        assertEquals(BaseUnit.G, i.unit)
        assertNull(i.price)
        assertEquals(Shelf.OTHER, i.shelf)
        assertEquals(VegKind.NONE, i.vegKind)
        assertFalse(i.archived)
        assertTrue(i.portions.isEmpty())
        val line = RecipeLine(text = "1 egg")
        assertNull(line.group)
        assertNull(line.ingredientId)
        assertNull(line.quantity)
        assertFalse(line.optional)
        val entry = ShoppingEntry()
        assertNull(entry.recipeId)
        assertFalse(entry.checked)
        assertEquals(ShoppingMark(5, checked = false, haveIt = false), ShoppingMark(5))
    }
}
