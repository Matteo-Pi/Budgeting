package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.allIngredients
import io.github.matteopi.budgeting.testing.allRecipes
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.ingredientNamed
import io.github.matteopi.budgeting.testing.recipeCard
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.food.IngredientsTags
import io.github.matteopi.budgeting.ui.food.foods.FoodListTags
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientEditTags
import io.github.matteopi.budgeting.ui.food.review.ReviewTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The built-in food list (docs/PLAN.md §11.12), with screenshots `foods-*`: chickpeas added from the list into the
 * Ingredients tab with their price and nutrition, a new ingredient filled in from the list, and a pasted recipe whose
 * lines come from the list, saved with its cost per portion.
 */
@RunWith(AndroidJUnit4::class)
class FoodListTest : UiTest() {

    private fun openIngredients() {
        seedOnboardingDone()
        seedRecipesSection()
        launchMain()
        waitForExists(clickableText("Ingredients"), "the Recipes section", HEAVY_TIMEOUT_MS)
        goToTab("Ingredients")
    }

    @Test
    fun chickpeasFromTheFoodListBecomeAnIngredientWithTheirPriceAndNutrition() {
        openIngredients()
        tap(hasTestTag(IngredientsTags.FOOD_LIST), "Food list")
        waitForExists(hasTestTag(FoodListTags.SEARCH), "the food list", HEAVY_TIMEOUT_MS)
        waitForText("Tesco prices", substring = true)
        shot("foods-01-list")

        typeInTag(FoodListTags.SEARCH, "chick")
        val chickpeas = hasTestTag(FoodListTags.row("chickpeas"))
        waitForExists(tagged(FoodListTags.row("chickpeas"), "£0.41", substring = true), "the chickpeas at 41p")
        waitForExists(tagged(FoodListTags.row("chickpeas"), "121 kcal", substring = true), "121 kcal per 100 g")
        shot("foods-02-search")
        tap(chickpeas, "the chickpeas")

        // The editor opens on the new ingredient: the food's price, nutrition and portions are its own now.
        waitForExists(hasTestTag(IngredientEditTags.SAVE), "the ingredient editor", HEAVY_TIMEOUT_MS)
        waitFor("the chickpeas to be added") { allIngredients().any { it.name == "Chickpeas (tin, drained)" } }
        val added = ingredientNamed("Chickpeas (tin, drained)")
        assertEquals(41L, added.price?.pence)
        assertEquals(240.0, added.packSize, 0.0)
        assertEquals(121.0, added.per100.kcal!!, 0.0)
        assertTrue(added.portions.any { it.name == "tin" && it.amount == 240.0 })
        shot("foods-03-added")

        // Back on the list it is already one of the ingredients, and a second tap opens that one.
        tapBack()
        waitForExists(tagged(FoodListTags.row("chickpeas"), "Already in your ingredients"), "already added")
        tap(chickpeas, "the chickpeas again")
        waitForExists(hasTestTag(IngredientEditTags.SAVE), "the same ingredient", HEAVY_TIMEOUT_MS)
        assertEquals(1, allIngredients().count { it.name == "Chickpeas (tin, drained)" })
        tapBack()
        tapBack()
        waitForExists(tagged(IngredientsTags.row(added.id), "£0.41", substring = true), "the chickpeas on the Ingredients tab")
        shot("foods-04-ingredients")
    }

    @Test
    fun aNewIngredientIsFilledInFromTheFoodList() {
        openIngredients()
        tapIcon("Add ingredient")
        waitForExists(hasTestTag(IngredientEditTags.SAVE), "the ingredient editor", HEAVY_TIMEOUT_MS)
        type("Name", "Basmati rice")
        waitForExists(hasTestTag(IngredientEditTags.FILL_FROM_FOODS), "Fill in from the food list")
        waitForExists(txt("353 kcal per 100 g", substring = true), "the food's energy")
        shot("foods-05-fill-offer")
        tap(hasTestTag(IngredientEditTags.FILL_FROM_FOODS), "Fill in from the food list")
        // With the nutrition filled in, the offer has done its job.
        waitUntilGone(hasTestTag(IngredientEditTags.FILL_FROM_FOODS), "the offer")
        tap(hasTestTag(IngredientEditTags.SAVE), "Save ingredient")
        waitFor("the rice to be saved") { allIngredients().any { it.name == "Basmati rice" } }
        val rice = ingredientNamed("Basmati rice")
        assertEquals(179L, rice.price?.pence)
        assertEquals(1000.0, rice.packSize, 0.0)
        assertEquals(353.0, rice.per100.kcal!!, 0.0)
        assertEquals(78.0, rice.per100.carbs!!, 0.0)
    }

    @Test
    fun aPastedRecipeTakesItsUnknownLinesFromTheFoodList() {
        openIngredients()
        goToTab("Recipes")
        tapIcon("Add recipe")
        waitForExists(hasTestTag(ReviewTags.PASTE_TEXT), "the paste box", HEAVY_TIMEOUT_MS)
        typeInTag(
            ReviewTags.PASTE_TEXT,
            "# Chickpea pilau\nServes: 2\n\n## Ingredients\n- 240 g chickpeas\n- 1 red onion\n- 150 g basmati rice\n- 300 ml water\n\n" +
                "## Method\n1. Cook it all.\n",
        )
        tap(hasTestTag(ReviewTags.READ), "Read recipe")
        waitForExists(hasTestTag(ReviewTags.SAVE), "the review")

        waitForExists(tagged(ReviewTags.line(1, "status"), "From the food list: Chickpeas (tin, drained)"), "the chickpeas")
        waitForExists(tagged(ReviewTags.line(2, "status"), "From the food list: Red onions"), "the onion")
        waitForExists(tagged(ReviewTags.line(3, "status"), "From the food list: Basmati rice"), "the rice")
        tap(hasTestTag(ReviewTags.line(4, "not-counted")), "Not counted on the water")
        // 41p of chickpeas, 120 g of a 95p kilo of onions and 150 g of a £1.79 kilo of rice: 79.25p for two.
        scrollIntoView(hasTestTag(ReviewTags.COST))
        waitForExists(tagged(ReviewTags.COST, "£0.40"), "the cost per portion")
        shot("foods-06-review")

        tap(hasTestTag(ReviewTags.SAVE), "Save recipe")
        waitForText("Chickpea pilau", timeoutMs = HEAVY_TIMEOUT_MS)
        val recipe = allRecipes().single { it.title == "Chickpea pilau" }
        val names = allIngredients().associate { it.id to it.name }
        assertEquals(
            listOf("Chickpeas (tin, drained)", "Red onions", "Basmati rice", null),
            recipe.lines.map { line -> line.ingredientId?.let { names[it] } },
        )
        // The names are learned, so the next recipe with "red onion" finds the ingredient itself.
        val aliases = blocking { appContainer.ingredientRepository.aliasMap() }
        assertEquals(ingredientNamed("Red onions").id, aliases["red onion"])
        tapBack()
        waitForExists(recipeCard("Chickpea pilau", "£0.40"), "the card")
        shot("foods-07-saved")
    }
}
