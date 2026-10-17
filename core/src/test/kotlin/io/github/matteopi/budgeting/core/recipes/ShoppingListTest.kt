package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingListTest {

    private val today = LocalDate.of(2026, 10, 17)

    private fun ing(
        id: Long,
        name: String,
        shelf: Shelf,
        pack: Double,
        price: Long?,
        unit: BaseUnit = BaseUnit.G,
    ) = Ingredient(id = id, name = name, unit = unit, packSize = pack, packLabel = "pack of ${pack.toInt()}", price = price?.let { Money(it) }, priceDate = today, shelf = shelf)

    private val onion = ing(1, "Onion", Shelf.FRUIT_VEG, 1000.0, 100)
    private val chicken = ing(2, "Chicken", Shelf.MEAT, 500.0, 400)
    private val milk = ing(3, "Milk", Shelf.DAIRY, 1000.0, 120, BaseUnit.ML)
    private val peas = ing(4, "Peas", Shelf.FROZEN, 800.0, 150)
    private val rice = ing(5, "Rice", Shelf.CUPBOARD, 1000.0, 200)
    private val oil = ing(6, "Olive oil", Shelf.OILS, 500.0, 600, BaseUnit.ML)
    private val cumin = ing(7, "Cumin", Shelf.HERBS_SPICES, 40.0, 110)
    private val bread = ing(8, "Bread", Shelf.OTHER, 800.0, null)
    private val apple = ing(9, "Apple", Shelf.FRUIT_VEG, 500.0, 180)
    private val all = listOf(onion, chicken, milk, peas, rice, oil, cumin, bread, apple)

    private fun line(id: Long, quantity: Double, optional: Boolean = false) =
        RecipeLine(text = "x", ingredientId = id, quantity = quantity, optional = optional)

    private val curry = Recipe(
        id = 10, title = "Curry", serves = 2,
        lines = listOf(line(1, 300.0), line(2, 400.0), line(5, 150.0, optional = true), RecipeLine(text = "4 tbsp water")),
    )
    private val stew = Recipe(
        id = 11, title = "Stew", serves = 4,
        lines = listOf(line(1, 400.0), line(4, 200.0), line(6, 30.0)),
    )
    private val recipes = listOf(curry, stew)

    private val prices = Prices.resolveAll(all, emptyList(), today)

    private fun build(
        entries: List<ShoppingEntry>,
        marks: List<ShoppingMark> = emptyList(),
        countOptional: Boolean = true,
    ) = ShoppingList.build(entries, marks, recipes, all, prices, countOptional)

    private fun recipeEntry(recipe: Long, servings: Int? = null, id: Long = recipe) = ShoppingEntry(id = id, recipeId = recipe, servings = servings)

    @Test
    fun `two recipes sharing an ingredient are summed and scaled`() {
        val list = build(listOf(recipeEntry(10, 4), recipeEntry(11, 4)))
        val onions = list.lines.first { it.ingredientId == 1L }
        // Curry for 4 is twice 300 g, the stew for 4 is 400 g.
        assertEquals(1000.0, onions.quantity, 1e-9)
        assertEquals(listOf(10L, 11L), onions.recipeIds)
        assertFalse(onions.isExtra)
        assertEquals(800.0, list.lines.first { it.ingredientId == 2L }.quantity, 1e-9)
        assertEquals(300.0, list.lines.first { it.ingredientId == 5L }.quantity, 1e-9)
    }

    @Test
    fun `an entry without servings uses the recipe's own`() {
        val list = build(listOf(recipeEntry(10)))
        assertEquals(300.0, list.lines.first { it.ingredientId == 1L }.quantity, 1e-9)
        assertEquals(400.0, list.lines.first { it.ingredientId == 2L }.quantity, 1e-9)
    }

    @Test
    fun `packs are rounded up, but an exact multiple is not`() {
        val list = build(listOf(recipeEntry(10, 4), recipeEntry(11, 4)))
        // 1000 g of onions is exactly one 1000 g pack.
        assertEquals(1, list.lines.first { it.ingredientId == 1L }.packs)
        // 800 g of chicken is two 500 g packs.
        val chickenLine = list.lines.first { it.ingredientId == 2L }
        assertEquals(2, chickenLine.packs)
        assertEquals(Money(800), chickenLine.cost)
        // 30 ml of oil is one pack.
        assertEquals(1, list.lines.first { it.ingredientId == 6L }.packs)
        val over = build(listOf(recipeEntry(10, 4), ShoppingEntry(id = 99, ingredientId = 1, quantity = 400.5)))
        assertEquals(2, over.lines.first { it.ingredientId == 1L }.packs, "1000.5 g needs a second pack")
    }

    @Test
    fun `I have it makes zero packs and no cost, but the line stays`() {
        val list = build(listOf(recipeEntry(10, 4)), marks = listOf(ShoppingMark(2, haveIt = true)))
        val chickenLine = list.lines.first { it.ingredientId == 2L }
        assertTrue(chickenLine.haveIt)
        assertEquals(0, chickenLine.packs)
        assertEquals(Money.ZERO, chickenLine.cost)
        assertEquals(800.0, chickenLine.quantity, 1e-9)
        assertEquals(setOf(1L, 2L, 5L), list.lines.map { it.ingredientId }.toSet())
        assertEquals(2, list.header.items, "the chicken is no longer to buy")
    }

    @Test
    fun `ticks come from the marks`() {
        val list = build(listOf(recipeEntry(10)), marks = listOf(ShoppingMark(1, checked = true), ShoppingMark(2, haveIt = true)))
        assertTrue(list.lines.first { it.ingredientId == 1L }.checked)
        assertFalse(list.lines.first { it.ingredientId == 2L }.checked)
        assertFalse(list.lines.first { it.ingredientId == 5L }.checked)
    }

    @Test
    fun `optional lines follow the switch`() {
        val on = build(listOf(recipeEntry(10)), countOptional = true)
        val off = build(listOf(recipeEntry(10)), countOptional = false)
        assertTrue(on.lines.any { it.ingredientId == 5L })
        assertTrue(off.lines.none { it.ingredientId == 5L })
    }

    @Test
    fun `an extra ingredient adds its quantity, or a whole pack`() {
        val list = build(
            listOf(
                recipeEntry(10),
                ShoppingEntry(id = 20, ingredientId = 1, quantity = 800.0),
                ShoppingEntry(id = 21, ingredientId = 7),
            ),
        )
        val onions = list.lines.first { it.ingredientId == 1L }
        assertEquals(1100.0, onions.quantity, 1e-9)
        assertEquals(2, onions.packs)
        assertTrue(onions.isExtra)
        assertEquals(listOf(10L), onions.recipeIds)
        val cuminLine = list.lines.first { it.ingredientId == 7L }
        assertEquals(40.0, cuminLine.quantity)
        assertEquals(1, cuminLine.packs)
        assertTrue(cuminLine.isExtra)
        assertTrue(cuminLine.recipeIds.isEmpty())
    }

    @Test
    fun `free text items come last and are not counted as priced items`() {
        val list = build(
            listOf(
                recipeEntry(10),
                ShoppingEntry(id = 30, freeText = "kitchen roll"),
                ShoppingEntry(id = 31, freeText = "  birthday candles ", checked = true),
                ShoppingEntry(id = 32, freeText = "   "),
            ),
        )
        assertEquals(
            listOf(ShoppingFreeLine(30, "kitchen roll", false), ShoppingFreeLine(31, "birthday candles", true)),
            list.freeText,
        )
        assertEquals(2, list.header.freeTextItems)
        assertEquals(3, list.header.items)
    }

    @Test
    fun `shelves are in shop order and ingredients by name within a shelf`() {
        val list = build(
            listOf(
                recipeEntry(10, 4), recipeEntry(11, 4),
                ShoppingEntry(id = 40, ingredientId = 7),
                ShoppingEntry(id = 41, ingredientId = 3, quantity = 500.0),
                ShoppingEntry(id = 42, ingredientId = 8),
                ShoppingEntry(id = 43, ingredientId = 9, quantity = 100.0),
            ),
        )
        assertEquals(
            listOf(Shelf.FRUIT_VEG, Shelf.MEAT, Shelf.DAIRY, Shelf.FROZEN, Shelf.CUPBOARD, Shelf.OILS, Shelf.HERBS_SPICES, Shelf.OTHER),
            list.sections.map { it.shelf },
        )
        assertEquals(listOf("Apple", "Onion"), list.sections.first().lines.map { it.name })
        assertEquals(Shelf.entries, list.sections.map { it.shelf }, "all eight shelves, in declaration order")
    }

    @Test
    fun `shelves with nothing on them are left out`() {
        val list = build(listOf(recipeEntry(11)))
        assertEquals(listOf(Shelf.FRUIT_VEG, Shelf.FROZEN, Shelf.OILS), list.sections.map { it.shelf })
    }

    @Test
    fun `header numbers`() {
        val list = build(listOf(recipeEntry(10, 4), recipeEntry(11, 4)))
        val h = list.header
        // Onion 100 + chicken 2 x 400 + peas 150 + oil 600 + rice 200 = 1850 for 5 items.
        assertEquals(5, h.items)
        assertEquals(Money(1850), h.estimated)
        assertEquals(0, h.unpricedItems)
        assertFalse(h.estimateAtLeast)
        // What the two meals use, unrounded: curry for 4 (60 + 640 + 60) + stew (40 + 37.5 + 36) = 873.5 -> 874.
        assertEquals(Money(874), h.mealsCost)
        assertFalse(h.mealsAtLeast)
    }

    @Test
    fun `an ingredient with no price makes the estimate at least`() {
        val list = build(listOf(recipeEntry(10), ShoppingEntry(id = 50, ingredientId = 8, quantity = 400.0)))
        val breadLine = list.lines.first { it.ingredientId == 8L }
        assertNull(breadLine.cost)
        assertNull(breadLine.price)
        assertEquals(1, list.header.unpricedItems)
        assertTrue(list.header.estimateAtLeast)
        assertEquals(Money(100 + 400 + 200), list.header.estimated)
        assertFalse(list.header.mealsAtLeast, "bread isn't in a recipe")
        val withMissing = ShoppingList.build(
            listOf(recipeEntry(10)), emptyList(), recipes, all, prices - 2L,
        )
        assertTrue(withMissing.header.mealsAtLeast)
        assertEquals(1, withMissing.header.unpricedItems)
    }

    @Test
    fun `lines costing nothing to buy and recipe lines without an ingredient are not listed`() {
        val list = build(listOf(recipeEntry(10)))
        assertEquals(3, list.lines.size, "the water line has no ingredient")
        assertTrue(list.freeText.isEmpty())
    }

    @Test
    fun `entries pointing at missing recipes or ingredients are skipped`() {
        val list = build(listOf(recipeEntry(99), ShoppingEntry(id = 5, ingredientId = 404, quantity = 1.0)))
        assertTrue(list.isEmpty)
        assertEquals(0, list.header.items)
        assertEquals(Money.ZERO, list.header.estimated)
        assertEquals(Money.ZERO, list.header.mealsCost)
    }

    @Test
    fun `an empty list is empty`() {
        val list = build(emptyList())
        assertTrue(list.isEmpty)
        assertTrue(list.sections.isEmpty())
        assertEquals(0, list.header.items)
    }

    @Test
    fun `the same recipe added twice is summed`() {
        val list = build(listOf(recipeEntry(11, 4, id = 1), recipeEntry(11, 8, id = 2)))
        assertEquals(1200.0, list.lines.first { it.ingredientId == 1L }.quantity, 1e-9)
        assertEquals(listOf(11L), list.lines.first { it.ingredientId == 1L }.recipeIds)
        assertEquals(2, list.lines.first { it.ingredientId == 1L }.packs)
    }

    @Test
    fun `the starter recipes make a shopping list`() {
        val entries = listOf(
            ShoppingEntry(id = 1, recipeId = StarterBook.models.recipeKeys.getValue("stir-fry"), servings = 2),
            ShoppingEntry(id = 2, recipeId = StarterBook.models.recipeKeys.getValue("traybake"), servings = 2),
        )
        val list = ShoppingList.build(entries, emptyList(), StarterBook.recipes, StarterBook.ingredients, StarterBook.prices)
        // Both recipes use chicken breast, 320 g each: 640 g from a 320 g pack is two packs.
        val chickenLine = list.lines.first { it.name == "Chicken breast fillets" }
        assertEquals(640.0, chickenLine.quantity, 1e-9)
        assertEquals(2, chickenLine.packs)
        assertEquals(Money(488), chickenLine.cost)
        assertEquals(Shelf.MEAT, chickenLine.shelf)
        val first = assertNotNull(list.sections.firstOrNull())
        assertEquals(Shelf.FRUIT_VEG, first.shelf)
        // The meals' own cost is the two recipes' unrounded totals added: 617.8 + 765.48.
        assertEquals(Money(1383), list.header.mealsCost)
        assertTrue(list.header.estimated.pence > list.header.mealsCost.pence, "whole packs cost more than what goes in the meals")
    }
}
