package io.github.matteopi.budgeting.ui.food.foods

import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Shelf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The food list's rows (docs/PLAN.md §11.12). */
class FoodListMappingTest {
    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun aRowGivesThePackPriceAndTheEnergyPer100() {
        val row = FoodListMapping.row(FoodDatabase.byKey("chickpeas")!!, emptyMap(), today)
        assertEquals("Chickpeas (tin, drained)", row.name)
        assertEquals("£0.41 · 400 g tin (240 g drained) · Tesco 22 Oct", row.priceLine)
        assertEquals("121 kcal · 7.0 g protein per 100 g", row.nutritionLine)
        assertNull(row.existingId)

        val milk = FoodListMapping.row(FoodDatabase.byKey("milk_semi")!!, emptyMap(), today)
        assertTrue(milk.nutritionLine.endsWith("per 100 ml"), milk.nutritionLine)
        // A starter food keeps its own date.
        assertTrue(FoodListMapping.row(FoodDatabase.byKey("onion")!!, emptyMap(), today).priceLine.endsWith("Tesco 15 Oct"))
    }

    @Test
    fun aFoodTheUserHasSaysSoWhateverTheCase() {
        val mine = listOf(
            Ingredient(id = 7, name = "chickpeas (TIN, drained)"),
            Ingredient(id = 8, name = "Red onions", archived = true),
        )
        val existing = FoodListMapping.existing(mine)
        assertEquals(7L, FoodListMapping.row(FoodDatabase.byKey("chickpeas")!!, existing, today).existingId)
        assertNull(FoodListMapping.row(FoodDatabase.byKey("red_onions")!!, existing, today).existingId, "an archived one doesn't count")
    }

    @Test
    fun withoutASearchTheFoodsAreByShelfInShopOrder() {
        val sections = FoodListMapping.sections(FoodDatabase.all(), "", emptyMap(), today)
        assertEquals(Shelf.entries.filter { shelf -> FoodDatabase.all().any { it.ingredient.shelf == shelf } }, sections.map { it.shelf })
        assertEquals("Fruit & veg", sections.first().title)
        assertEquals(FoodDatabase.all().size, sections.sumOf { it.rows.size })
        val names = sections.first().rows.map { it.name.lowercase() }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun aSearchIsOneListBestFirst() {
        val sections = FoodListMapping.sections(FoodDatabase.search("chickpea"), "chickpea", emptyMap(), today)
        assertEquals(1, sections.size)
        assertNull(sections.single().title)
        assertEquals("chickpeas", sections.single().rows.first().key)
        assertTrue(FoodListMapping.sections(FoodDatabase.search("zzz"), "zzz", emptyMap(), today).isEmpty())
    }

    @Test
    fun theListSaysWhereItsNumbersComeFrom() {
        val about = FoodListMapping.about(today)
        assertTrue(about.startsWith("Tesco prices found online on 22 Oct"), about)
        assertTrue("typical nutrition" in about)
    }
}
