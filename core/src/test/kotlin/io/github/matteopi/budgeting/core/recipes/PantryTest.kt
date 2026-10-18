package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the kitchen has (docs/PLAN.md §11.11). */
class PantryTest {

    private val onion = Ingredient(
        id = 1, name = "Brown onions", packSize = 1000.0,
        portions = listOf(Portion("each", 150.0), Portion("tbsp", 9.0)),
    )
    private val rice = Ingredient(id = 2, name = "Rice", packSize = 1000.0)
    private val oil = Ingredient(id = 3, name = "Olive oil", unit = BaseUnit.ML, packSize = 500.0)

    @Test
    fun `the units offered are the base unit, the big unit, packs and the portions that are not spoons`() {
        assertEquals(
            listOf(PantryUnit("g", 1.0), PantryUnit("kg", 1000.0), PantryUnit("packs of 1 kg", 1000.0), PantryUnit("each (150 g)", 150.0)),
            Pantry.units(onion),
        )
        assertEquals(listOf("ml", "l", "packs of 500 ml"), Pantry.units(oil).map { it.label })
        assertEquals(450.0, Pantry.toBase(3.0, PantryUnit("each (150 g)", 150.0)))
        assertNull(Pantry.toBase(0.0, PantryUnit("g", 1.0)))
    }

    @Test
    fun `what is owned is described with a count or packs`() {
        assertEquals("450 g · about 3", Pantry.describe(OwnedItem(1, 450.0), onion))
        assertEquals("1.5 kg · about 1½ packs", Pantry.describe(OwnedItem(2, 1500.0), rice))
        assertEquals("1 kg · about 1 pack", Pantry.describe(OwnedItem(2, 1000.0), rice))
        assertEquals("300 g", Pantry.describe(OwnedItem(2, 300.0), rice))
        assertEquals("Some", Pantry.describe(OwnedItem(3, null), oil))
    }

    @Test
    fun `a recipe is covered by what is owned in at least its quantity, or owned unmeasured`() {
        val recipe = Recipe(
            title = "Rice and onions", serves = 2,
            lines = listOf(
                RecipeLine(text = "2 onions", ingredientId = 1, quantity = 300.0),
                RecipeLine(text = "1 onion, for the top", ingredientId = 1, quantity = 150.0),
                RecipeLine(text = "150 g rice", ingredientId = 2, quantity = 150.0),
                RecipeLine(text = "Oil", ingredientId = 3, optional = true),
                RecipeLine(text = "Salt to taste"),
            ),
        )
        val ingredients = listOf(onion, rice, oil).associateBy { it.id }
        val none = Pantry.coverage(recipe, ingredients, emptyMap())
        assertEquals(PantryCoverage(needed = 3, have = 0, missing = listOf(1L, 2L, 3L)), none)

        val some = mapOf(1L to OwnedItem(1, 400.0), 2L to OwnedItem(2, 1000.0), 3L to OwnedItem(3, null))
        val short = Pantry.coverage(recipe, ingredients, some)
        assertEquals(listOf(1L), short.missing, "400 g of onions is less than the 450 g needed")
        assertEquals(2, short.have)

        val enough = some + (1L to OwnedItem(1, 450.0))
        assertEquals(0, Pantry.coverage(recipe, ingredients, enough).missingCount)
        assertEquals(2, Pantry.coverage(recipe, ingredients, emptyMap(), countOptional = false).needed, "the optional oil is left out")
        assertTrue(Pantry.covers(OwnedItem(1, null), 1e9))
        assertFalse(Pantry.covers(null, 0.0))
    }
}
