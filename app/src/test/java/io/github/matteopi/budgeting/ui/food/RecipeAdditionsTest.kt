package io.github.matteopi.budgeting.ui.food

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.CookMethod
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.FoodGroupKind
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.OwnedItem
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.ShoppingLine
import io.github.matteopi.budgeting.ui.food.components.FoodBook
import io.github.matteopi.budgeting.ui.food.ingredient.FoodTexts
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientForm
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientFormRules
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientsMapping
import io.github.matteopi.budgeting.ui.food.ingredient.OwnedFilter
import io.github.matteopi.budgeting.ui.food.ingredient.ShoppingTexts
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Recipes filters, what the kitchen has and added sugars, as the screens show them (docs/PLAN.md §11.11). */
class RecipeAdditionsTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val models = RecipeBundle.starter().toModels()
    private val ingredients = models.ingredients.associateBy { it.id }
    private val recipes: List<Recipe> = models.recipes
    private fun ingredient(key: String): Ingredient = ingredients.getValue(models.ingredientKeys.getValue(key))

    private fun book(owned: Map<Long, OwnedItem> = emptyMap()) = FoodBook(
        recipes = recipes,
        ingredients = ingredients,
        prices = Prices.resolveAll(models.ingredients, emptyList(), today),
        countOptionalExtras = true,
        today = today,
        owned = owned,
    )

    private fun titles(filters: RecipeFilters, owned: Map<Long, OwnedItem> = emptyMap(), query: String = ""): Set<String> =
        RecipeListMapping.filter(RecipeListMapping.cards(book(owned)), query, filters).map { it.title }.toSet()

    @Test
    fun theFilterMenusOfferWhatTheRecipesHaveWithCounts() {
        val cards = RecipeListMapping.cards(book())
        assertEquals(
            listOf(FoodGroup.CHICKEN to 3, FoodGroup.BEEF to 1, FoodGroup.PORK to 1, FoodGroup.BEANS_LENTILS to 1),
            RecipeListMapping.groupChoices(cards, FoodGroupKind.PROTEIN),
        )
        assertEquals(listOf(FoodGroup.RICE to 3, FoodGroup.POTATOES to 1), RecipeListMapping.groupChoices(cards, FoodGroupKind.CARB))
        assertEquals(listOf(CookMethod.HOB to 3, CookMethod.OVEN to 1), RecipeListMapping.methodChoices(cards))
    }

    @Test
    fun eachFilterNarrowsTheRecipes() {
        assertEquals(setOf("Chilli"), titles(RecipeFilters(protein = FoodGroup.BEEF)))
        assertEquals(setOf("Stir-fry", "Traybake", "Braise"), titles(RecipeFilters(protein = FoodGroup.CHICKEN)))
        assertEquals(setOf("Traybake"), titles(RecipeFilters(carb = FoodGroup.POTATOES)))
        assertEquals(setOf("Traybake"), titles(RecipeFilters(method = CookMethod.OVEN)))
        assertEquals(setOf("Stir-fry"), titles(RecipeFilters(maxMins = 45)))
        assertEquals(setOf("Stir-fry", "Traybake", "Chilli"), titles(RecipeFilters(maxMins = 75)))
        assertEquals(emptySet(), titles(RecipeFilters(meatFree = true)))
        assertEquals(setOf("Braise"), titles(RecipeFilters(protein = FoodGroup.CHICKEN, method = CookMethod.HOB, carb = FoodGroup.RICE, maxMins = 90), query = "bacon"))
        assertEquals(setOf("Stir-fry", "Braise"), titles(RecipeFilters(protein = FoodGroup.CHICKEN, carb = FoodGroup.RICE)))
        assertTrue(RecipeFilters(maxMins = 45).active)
        assertTrue(!RecipeFilters().active)
    }

    @Test
    fun whatIHaveFiltersAndCountsTheRecipes() {
        // Everything in the stir-fry, measured or not.
        val stirFry = recipes.first { it.shortTitle == "Stir-fry" }
        val owned = stirFry.lines.mapNotNull { it.ingredientId }.distinct().associateWith { OwnedItem(it, null, 0) }
        val cards = RecipeListMapping.cards(book(owned)).associateBy { it.title }
        assertEquals(0, cards.getValue("Stir-fry").missingCount)
        assertEquals("You have ${cards.getValue("Stir-fry").ingredientCount} of ${cards.getValue("Stir-fry").ingredientCount} ingredients", cards.getValue("Stir-fry").haveText)
        assertEquals(setOf("Stir-fry"), titles(RecipeFilters(have = HaveFilter.EVERYTHING), owned))
        assertTrue(cards.getValue("Chilli").missingCount > 2)
        assertTrue("Chilli" !in titles(RecipeFilters(have = HaveFilter.MISSING_TWO), owned))
    }

    @Test
    fun ingredientRowsSayWhatIsOwnedAndTheTabFiltersOnIt() {
        val onion = ingredient("onion")
        val rice = ingredient("rice")
        val noon = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val owned = mapOf(onion.id to OwnedItem(onion.id, 450.0, noon), rice.id to OwnedItem(rice.id, null, noon))
        val prices = Prices.resolveAll(models.ingredients, emptyList(), today)
        val mine = IngredientsMapping.sections(models.ingredients, prices, "", today, owned, OwnedFilter.OWNED)
        assertEquals(setOf("Brown onions", "Long-grain white rice"), mine.flatMap { it.rows }.map { it.name }.toSet())
        assertEquals("Have 450 g · about 3 · 17 Oct", mine.flatMap { it.rows }.first { it.name == "Brown onions" }.ownedText)
        assertEquals("Have some · 17 Oct", IngredientsMapping.ownedText(owned.getValue(rice.id), rice, today, ZoneOffset.UTC))
        val others = IngredientsMapping.sections(models.ingredients, prices, "", today, owned, OwnedFilter.NOT_OWNED)
        assertEquals(models.ingredients.size - 2, others.sumOf { it.rows.size })
        assertNull(others.flatMap { it.rows }.first().ownedText)
    }

    private fun line(quantity: Double, owned: OwnedItem?, packs: Int) = ShoppingLine(
        ingredientId = 1, name = "Rice", shelf = Shelf.CUPBOARD, unit = BaseUnit.G, quantity = quantity, packSize = 1000.0,
        packLabel = "1 kg", packs = packs, price = null, cost = Money(150), checked = false, haveIt = false,
        recipeIds = emptyList(), isExtra = false, owned = owned,
    )

    @Test
    fun shoppingLinesSayWhatIsOwned() {
        assertEquals("800 g · 1 pack of 1 kg", ShoppingTexts.quantityLine(line(800.0, null, 1)))
        assertEquals("1.5 kg · you have 400 g · 2 packs of 1 kg", ShoppingTexts.quantityLine(line(1500.0, OwnedItem(1, 400.0), 2)))
        assertEquals("800 g · you have 1 kg, enough", ShoppingTexts.quantityLine(line(800.0, OwnedItem(1, 1000.0), 0)))
        assertNull(ShoppingTexts.costText(line(800.0, OwnedItem(1, 1000.0), 0)))
        assertEquals("800 g · you have some · 1 pack of 1 kg", ShoppingTexts.quantityLine(line(800.0, OwnedItem(1, null), 1)))
    }

    @Test
    fun theEditorKeepsAddedSugarsAndTheFoodGroup() {
        val honey = ingredient("honey").copy(foodGroup = FoodGroup.NONE)
        val form = IngredientFormRules.fromIngredient(honey)
        assertEquals("82.4", form.nutrition[Nutrient.ADDED_SUGARS])
        assertEquals(FoodGroup.NONE, form.foodGroup)
        val built = IngredientFormRules.build(form.copy(nutrition = form.nutrition + (Nutrient.ADDED_SUGARS to "80"), foodGroup = null), honey, today)
        assertEquals(80.0, built?.per100?.addedSugars)
        assertNull(built?.foodGroup)
        assertEquals("Added sugars (g)", IngredientFormRules.nutrientLabel(Nutrient.ADDED_SUGARS))
        assertTrue(IngredientFormRules.errors(IngredientForm(name = "X", packSizeText = "1", nutrition = mapOf(Nutrient.ADDED_SUGARS to "-1"))).any)
    }

    @Test
    fun theCountsAsMenuWordsItsChoices() {
        assertEquals("Automatic (Chicken)", FoodTexts.foodGroupName(null, FoodGroup.CHICKEN))
        assertEquals("Automatic (neither a protein nor a carb)", FoodTexts.foodGroupName(null, null))
        assertEquals("Rice (carb)", FoodTexts.foodGroupName(FoodGroup.RICE, null))
        assertEquals("Beef (protein)", FoodTexts.foodGroupName(FoodGroup.BEEF, null))
        assertEquals("Neither a protein nor a carb", FoodTexts.foodGroupName(FoodGroup.NONE, FoodGroup.BEEF))
    }
}
