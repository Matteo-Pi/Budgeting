package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.receipts.PurchasedItem
import io.github.matteopi.budgeting.core.receipts.Store
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.ShoppingEntry
import io.github.matteopi.budgeting.core.recipes.ShoppingList
import io.github.matteopi.budgeting.core.recipes.ShoppingMark
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoodTextsTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val models = RecipeBundle.starter().toModels()
    private val ingredients = models.ingredients
    private val onion = ingredients.first { it.name == "Brown onions" }

    // ---- the Ingredients tab ------------------------------------------------------------------

    @Test
    fun aTypedPriceShowsItsPackAndDate() {
        val price = Prices.resolve(onion, emptyList(), today)
        assertEquals("£0.99 · 1 kg bag · typed 15 Oct", FoodTexts.priceLine(onion, price, today))
        assertEquals("£0.99 · 1 kg bag · typed 15 Oct 2026", FoodTexts.priceLine(onion, price, LocalDate.of(2027, 1, 5)))
    }

    @Test
    fun aTypedPriceWithoutALabelOrDateStillReads() {
        val plain = Ingredient(id = 1, name = "Flour", packSize = 1500.0, price = Money(155))
        assertEquals("£1.55 · 1.5 kg · typed", FoodTexts.priceLine(plain, Prices.resolve(plain, emptyList(), today), today))
        assertNull(FoodTexts.priceLine(plain, null, today))
    }

    @Test
    fun aReceiptPriceSaysWhatWasPaidWhereAndWhen() {
        val linked = onion.copy(productId = 9)
        val bought = PurchasedItem(productId = 9, date = LocalDate.of(2026, 11, 3), store = Store.TESCO, price = Money(89))
        val price = Prices.resolve(linked, listOf(bought), LocalDate.of(2026, 11, 20))
        assertEquals("paid £0.89 at Tesco 3 Nov", FoodTexts.priceLine(linked, price, LocalDate.of(2026, 11, 20)))
    }

    @Test
    fun theShelvesAreInShopOrderAndRowsByName() {
        val prices = Prices.resolveAll(ingredients, emptyList(), today)
        val sections = IngredientsMapping.sections(ingredients, prices, "", today)
        assertEquals(Shelf.entries.filter { shelf -> ingredients.any { it.shelf == shelf } }, sections.map { it.shelf })
        for (section in sections) {
            val names = section.rows.map { it.name.lowercase() }
            assertEquals(names.sorted(), names, section.title)
        }
        assertEquals("Fruit & veg", sections.first().title)
        assertEquals(ingredients.size, sections.sumOf { it.rows.size })
    }

    @Test
    fun searchingKeepsTheMatchingWordsAndArchivedRowsAreHidden() {
        val prices = Prices.resolveAll(ingredients, emptyList(), today)
        val onions = IngredientsMapping.sections(ingredients, prices, "ONION", today)
        assertEquals(listOf("Brown onions"), onions.flatMap { it.rows }.map { it.name })

        val several = IngredientsMapping.sections(ingredients, prices, "chicken stock", today)
        assertEquals(listOf("Chicken stock cubes"), several.flatMap { it.rows }.map { it.name })
        assertTrue(IngredientsMapping.sections(ingredients, prices, "zzz", today).isEmpty())

        val hidden = ingredients.map { if (it.id == onion.id) it.copy(archived = true) else it }
        assertFalse(IngredientsMapping.sections(hidden, prices, "", today).flatMap { it.rows }.any { it.name == onion.name })
    }

    @Test
    fun rowsFlagMissingPricesAndNutritionAndOldPrices() {
        val bare = Ingredient(id = 50, name = "Mystery", shelf = Shelf.OTHER)
        val old = onion.copy(priceDate = LocalDate.of(2026, 1, 1), per100 = Nutrition())
        val all = listOf(bare, old)
        val sections = IngredientsMapping.sections(all, Prices.resolveAll(all, emptyList(), today), "", today)
        val mystery = sections.flatMap { it.rows }.first { it.name == "Mystery" }
        assertTrue(mystery.noPrice)
        assertTrue(mystery.noNutrition)
        assertNull(mystery.priceLine)
        val aged = sections.flatMap { it.rows }.first { it.name == "Brown onions" }
        assertTrue(aged.old)
        assertFalse(aged.noPrice)
        assertTrue(aged.noNutrition)
        assertEquals("£0.99 · 1 kg bag · typed 1 Jan", aged.priceLine)
    }

    // ---- the Shopping tab ----------------------------------------------------------------------

    private val stirFry = models.recipes.first { it.title.startsWith("Chicken & Vegetable") }
    private val traybake = models.recipes.first { it.title.startsWith("Chicken, Potato") }

    private fun build(marks: List<ShoppingMark> = emptyList()) = ShoppingList.build(
        entries = listOf(
            ShoppingEntry(id = 1, recipeId = stirFry.id, servings = 2),
            ShoppingEntry(id = 2, recipeId = traybake.id, servings = 2),
            ShoppingEntry(id = 3, freeText = "Bin bags"),
        ),
        marks = marks,
        recipes = models.recipes,
        ingredients = ingredients,
        prices = Prices.resolveAll(ingredients, emptyList(), today),
    )

    @Test
    fun linesSayWhatIsNeededAndHowManyPacks() {
        val list = build()
        val onions = list.lines.first { it.name == "Brown onions" }
        assertEquals("300 g · 1 pack of 1 kg", ShoppingTexts.quantityLine(onions))
        val chicken = list.lines.first { it.name == "Chicken breast fillets" }
        assertEquals("640 g · 2 packs of 320 g", ShoppingTexts.quantityLine(chicken))
        assertEquals("£4.88", ShoppingTexts.costText(chicken))
        assertEquals("£0.99", ShoppingTexts.costText(onions))
    }

    @Test
    fun theLinesNameTheRecipesThatNeedThem() {
        val names = models.recipes.associate { it.id to ShoppingTexts.recipeName(it) }
        assertEquals(
            listOf("Stir-fry", "Traybake", "Braise", "Chilli"),
            models.recipes.map { ShoppingTexts.recipeName(it) },
        )
        assertEquals("Beef", ShoppingTexts.recipeName(models.recipes.first().copy(shortTitle = null, title = "Beef")))
        val onions = build().lines.first { it.name == "Brown onions" }
        assertEquals("For Stir-fry, Traybake", ShoppingTexts.neededFor(onions, names))
        val extra = onions.copy(recipeIds = emptyList(), isExtra = true)
        assertEquals("For extra item", ShoppingTexts.neededFor(extra, names))
        assertNull(ShoppingTexts.neededFor(onions.copy(recipeIds = emptyList()), names))
    }

    @Test
    fun theHeaderCountsItemsAndMarkingIHaveItChangesIt() {
        val before = build()
        val line = ShoppingTexts.headerLine(before.header)
        assertTrue(line.startsWith("About £"), line)
        assertTrue(line.endsWith("for ${before.header.items} items"), line)
        assertEquals("${before.header.mealsCost.format()} of it goes into these meals", ShoppingTexts.mealsLine(before.header, true))
        assertNull(ShoppingTexts.mealsLine(before.header, false))
        assertNull(ShoppingTexts.unpricedLine(before.header))

        val onion = before.lines.first { it.name == "Brown onions" }
        val after = build(listOf(ShoppingMark(onion.ingredientId, haveIt = true)))
        assertEquals(before.header.items - 1, after.header.items)
        assertEquals(before.header.estimated - Money(99), after.header.estimated)
        val have = after.lines.first { it.name == "Brown onions" }
        assertEquals("300 g · you have it", ShoppingTexts.quantityLine(have))
        assertNull(ShoppingTexts.costText(have))
    }

    @Test
    fun anItemWithNoPriceIsCountedAndSaid() {
        val unpriced = ingredients.map { if (it.name == "Okra") it.copy(price = null) else it }
        val list = ShoppingList.build(
            entries = listOf(ShoppingEntry(id = 1, recipeId = traybake.id, servings = 2)),
            marks = emptyList(),
            recipes = models.recipes,
            ingredients = unpriced,
            prices = Prices.resolveAll(unpriced, emptyList(), today),
        )
        assertEquals("1 item has no price", ShoppingTexts.unpricedLine(list.header))
        assertEquals("No price", ShoppingTexts.costText(list.lines.first { it.name == "Okra" }))
        assertEquals("1 item", FoodTexts.items(1))
        assertEquals("1 pack", FoodTexts.packs(1))
        assertEquals("1 serving", ShoppingTexts.servings(1))
        assertEquals("3 servings", ShoppingTexts.servings(3))
    }
}
