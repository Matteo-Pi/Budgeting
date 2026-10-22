package io.github.matteopi.budgeting.ui

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.core.recipes.RecipeText
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.allRecipes
import io.github.matteopi.budgeting.testing.ingredientNamed
import io.github.matteopi.budgeting.testing.recipeCard
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.seedStarterBook
import io.github.matteopi.budgeting.testing.starterRecipe
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.testApp
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.food.review.ReviewTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adding recipes (docs/PLAN.md §11.4, §11.8): the chilli pasted as text and matched line by line against the starter
 * book (with the food list's names), an ingredient created on the review and matched by itself the next time, and
 * recipe text shared into the app opening the recipe review while a receipt's text still opens the receipt review.
 */
@RunWith(AndroidJUnit4::class)
class AddRecipeTest : UiTest() {

    private fun openRecipes() {
        seedOnboardingDone()
        seedRecipesSection()
        launchMain()
    }

    /** The chilli of the starter book in the paste format, titled "My chilli". */
    private fun chilliText(): String = RecipeText.format(starterRecipe("Chilli").copy(title = "My chilli"))

    /** The review's key of the chilli's line containing [fragment]: lines are keyed from 1 in reading order. */
    private fun chilliLineKey(fragment: String): Long =
        starterRecipe("Chilli").lines.indexOfFirst { fragment in it.text }.also { check(it >= 0) { fragment } } + 1L

    private fun pasteAndRead(text: String) {
        tapIcon("Add recipe")
        waitForExists(hasTestTag(ReviewTags.PASTE_TEXT), "the paste box", HEAVY_TIMEOUT_MS)
        typeInTag(ReviewTags.PASTE_TEXT, text)
        tap(hasTestTag(ReviewTags.READ), "Read recipe")
        waitForExists(hasTestTag(ReviewTags.SAVE), "the review")
    }

    @Test
    fun aPastedRecipeIsMatchedToTheIngredientsReviewedAndSaved() {
        seedStarterBook()
        openRecipes()
        pasteAndRead(chilliText())
        shot("add-recipe-01-review")

        // "Rice" is the food list's Long-grain white rice, which the book has; the lemon is only a close match, so the
        // review suggests it; the water doesn't count.
        waitForExists(tagged(ReviewTags.line(chilliLineKey("400 g rice"), "status"), "→ Long-grain white rice"), "the rice")
        val lemon = ingredientNamed("Lemon (juice)").id
        tap(hasTestTag(ReviewTags.line(chilliLineKey("½ lemon"), "suggestion-$lemon")), "the lemon suggestion")
        tap(hasTestTag(ReviewTags.line(chilliLineKey("boiling water"), "not-counted")), "Not counted on the water")

        // The live numbers are the chilli's: the salt to taste has no amount, which changes neither.
        scrollIntoView(hasTestTag(ReviewTags.COST))
        waitForExists(tagged(ReviewTags.COST, "£2.54"), "the cost per portion")
        waitForExists(tagged(ReviewTags.KCAL, "897 kcal"), "the kcal")
        shot("add-recipe-02-matched")

        tap(hasTestTag(ReviewTags.SAVE), "Save recipe")
        waitForText("My chilli", timeoutMs = HEAVY_TIMEOUT_MS)
        assertEquals(5, allRecipes().count { !it.archived })

        // Back from the new recipe goes to the Recipes tab (the add screen is gone), which has five cards.
        tapBack()
        waitForExists(recipeCard("My chilli", "£2.54"), "the new card")
        waitForExists(recipeCard("Chilli", "£2.54"), "the starter chilli's card")
        shot("add-recipe-03-saved")
    }

    @Test
    fun anIngredientCreatedOnTheReviewIsMatchedByItselfNextTime() {
        seedStarterBook()
        openRecipes()
        pasteAndRead("# Tempeh salad\nServes: 2\n\n## Ingredients\n- 200 g tempeh\n\n## Method\n1. Fry the tempeh.\n")

        // The tempeh is in neither the book nor the food list: create it on the line.
        tap(hasTestTag(ReviewTags.line(1, "create")), "Create ingredient on the tempeh line")
        waitForExists(hasTestTag(ReviewTags.CREATE_NAME), "the new ingredient form")
        typeInTag(ReviewTags.CREATE_PACK_SIZE, "225")
        typeInTag(ReviewTags.CREATE_PRICE, "2.50")
        shot("add-recipe-04-create")
        tap(hasTestTag(ReviewTags.CREATE_SUBMIT), "Create")
        waitForExists(tagged(ReviewTags.line(1, "status"), "→ Tempeh"), "the tempeh on its line")

        // 200 g of a £2.50 pack of 225 g, for two: £1.11 a portion.
        waitForExists(tagged(ReviewTags.COST, "£1.11"), "the cost per portion")
        tap(hasTestTag(ReviewTags.SAVE), "Save recipe")
        waitForText("Tempeh salad", timeoutMs = HEAVY_TIMEOUT_MS)
        assertNotNull(allRecipes().firstOrNull { it.title == "Tempeh salad" })
        tapBack()

        // A second recipe with tempeh finds it by name.
        pasteAndRead("# Tempeh wrap\nServes: 1\n\n## Ingredients\n- 50 g tempeh\n\n## Method\n1. Wrap it.\n")
        waitForExists(tagged(ReviewTags.line(1, "status"), "→ Tempeh"), "the tempeh matched by itself")
        waitForExists(tagged(ReviewTags.COST, "£0.56"), "the wrap's cost")
        shot("add-recipe-05-matched-by-itself")
    }

    @Test
    fun recipeTextSharedIntoTheAppOpensTheRecipeReview() {
        seedStarterBook()
        seedOnboardingDone()
        val share = Intent(Intent.ACTION_SEND)
            .setClass(testApp, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, chilliText())
        launchMain(share)
        waitForExists(hasTestTag(ReviewTags.SAVE), "the recipe review", HEAVY_TIMEOUT_MS)
        waitForText("My chilli")
        shot("add-recipe-06-shared")
    }

    @Test
    fun receiptTextSharedIntoTheAppStillOpensTheReceiptReview() {
        seedOnboardingDone()
        val share = Intent(Intent.ACTION_SEND)
            .setClass(testApp, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "CORNER SHOP LTD\nMilk 1.20\nTOTAL   1.20")
        launchMain(share)
        waitForExists(txt("Review receipt"), "the receipt review", HEAVY_TIMEOUT_MS)
    }
}
