package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.RecipeText
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.allRecipes
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.clipboardText
import io.github.matteopi.budgeting.testing.ingredientNamed
import io.github.matteopi.budgeting.testing.recipeCard
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.seedStarterBook
import io.github.matteopi.budgeting.testing.setClipboard
import io.github.matteopi.budgeting.testing.starterRecipe
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.food.IngredientsTags
import io.github.matteopi.budgeting.ui.food.RecipesTags
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientEditTags
import io.github.matteopi.budgeting.ui.food.ingredient.OwnedFilter
import io.github.matteopi.budgeting.ui.food.ingredient.OwnedTags
import io.github.matteopi.budgeting.ui.food.recipe.RecipeDetailTags
import io.github.matteopi.budgeting.ui.food.review.ReviewTags
import io.github.matteopi.budgeting.ui.food.timers.TimerTags
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The additions of docs/PLAN.md §11.11 on the starter book: veg in total and towards 5 a day, added sugars, timers
 * before and after *Cook*, what the kitchen has, the Recipes filters, and pasting a recipe copied from a chat or with
 * *Copy recipe*.
 */
@RunWith(AndroidJUnit4::class)
class RecipeAdditionsTest : UiTest() {

    private fun openRecipes(starter: Boolean = true) {
        if (starter) seedStarterBook()
        seedOnboardingDone()
        seedRecipesSection()
        launchMain()
    }

    @Test
    fun theChilliShowsItsVegInTotalAndTowards5ADayAndTheStirFryItsAddedSugars() {
        openRecipes()
        tap(recipeCard("Chilli", "£2.54"), "the chilli card")
        waitForText("Beef, Bean & Lentil Chilli", timeoutMs = HEAVY_TIMEOUT_MS)
        scrollIntoView(hasTestTag(RecipeDetailTags.VEG_EFFECTIVE))
        waitForExists(tagged(RecipeDetailTags.VEG_EFFECTIVE, "5.9 towards your 5 a day"), "veg towards 5 a day")
        waitForExists(tagged(RecipeDetailTags.VEG_TOTAL, "6.7 portions of veg and pulses in total"), "veg in total")
        waitForText("1.8, counted as 1.0")
        shot("additions-01-chilli-veg")
        tapBack()

        tap(recipeCard("Stir-fry", "£3.09"), "the stir-fry card")
        waitForText("Chicken & Vegetable Stir-Fry", timeoutMs = HEAVY_TIMEOUT_MS)
        scrollIntoView(hasTestTag(RecipeDetailTags.ADDED_SUGARS))
        waitForExists(tagged(RecipeDetailTags.ADDED_SUGARS, "7.5 g"), "the added sugars")
        waitForExists(tagged(RecipeDetailTags.ADDED_SUGARS, "25% of the 30 g a day limit"), "the added sugars against the limit")
        shot("additions-02-added-sugars")
    }

    @Test
    fun timersStartBeforeCookKeepRunningInTheCookingViewAndSayTimesUp() {
        openRecipes()
        tap(recipeCard("Stir-fry", "£3.09"), "the stir-fry card")
        waitForText("Chicken & Vegetable Stir-Fry", timeoutMs = HEAVY_TIMEOUT_MS)

        // Step 6, Aromatics, has "about 30 sec".
        tap(hasTestTag(TimerTags.chip(5, 0)), "the Aromatics timer")
        waitForExists(tagged(TimerTags.BAR, "Aromatics"), "the running timer")
        // A custom timer runs beside it, and Cancel stops it.
        tap(hasTestTag(TimerTags.CUSTOM), "the custom timer")
        typeInTag(TimerTags.CUSTOM_MINUTES, "5")
        tap(hasTestTag(TimerTags.CUSTOM_START), "Start")
        waitForExists(tagged(TimerTags.BAR, "Timer"), "the custom timer running")
        shot("additions-03-timers")
        tap(hasTestTag(TimerTags.cancel(1)), "Cancel on the custom timer")
        waitUntilGone(tagged(TimerTags.BAR, "Timer"), "the custom timer")

        // After Cook the timer is still counting, and its end is announced there.
        tap(hasTestTag(RecipeDetailTags.COOK), "Cook")
        waitForExists(tagged(TimerTags.BAR, "Aromatics"), "the timer in the cooking view")
        waitForExists(hasTestTag(TimerTags.ALERT), "Time's up", timeoutMs = 60_000)
        waitForText("Time's up: Aromatics")
        shot("additions-04-times-up")
        tap(hasTestTag(TimerTags.ALERT_DISMISS), "Dismiss")
        waitUntilGone(hasTestTag(TimerTags.ALERT), "the alert")
        waitUntilGone(hasTestTag(TimerTags.BAR), "the timers bar")
    }

    @Test
    fun whatIHaveIsSetOnTheIngredientsTabFiltersItAndCoversTheShoppingList() {
        openRecipes()
        val rice = ingredientNamed("Long-grain white rice")
        val onion = ingredientNamed("Brown onions")
        blocking { appContainer.shoppingRepository.addRecipe(starterRecipe("Stir-fry").id, 2) }

        goToTab("Ingredients")
        tap(hasTestTag(IngredientsTags.owned(rice.id)), "How much rice I have")
        typeInTag(OwnedTags.AMOUNT, "1")
        tap(hasTestTag(OwnedTags.unit(1)), "kg")
        tap(hasTestTag(OwnedTags.SAVE), "Save")
        waitForExists(tagged(IngredientsTags.row(rice.id), "Have 1 kg · about 1 pack", substring = true), "the rice owned")
        assertEquals(1000.0, blocking { appContainer.ingredientRepository.observeOwned().first() }[rice.id]?.quantity)

        tap(hasTestTag(IngredientsTags.filter(OwnedFilter.OWNED)), "I have")
        waitUntilGone(hasTestTag(IngredientsTags.row(onion.id)), "the onions, which aren't owned")
        waitForExists(hasTestTag(IngredientsTags.row(rice.id)), "the rice")
        shot("additions-05-i-have")

        goToTab("Shopping")
        waitForText("you have 1 kg, enough", substring = true, timeoutMs = HEAVY_TIMEOUT_MS)
        shot("additions-06-shopping-owned")

        goToTab("Recipes")
        waitForText("You have 1 of", substring = true, timeoutMs = HEAVY_TIMEOUT_MS)
    }

    @Test
    fun theRecipesFilterByProteinMethodAndTimeAndAnIngredientCanCountAsAnother() {
        openRecipes()
        waitForExists(recipeCard("Stir-fry"), "the stir-fry card", HEAVY_TIMEOUT_MS)

        tap(hasTestTag(RecipesTags.FILTER_PROTEIN), "Protein")
        tap(hasTestTag(RecipesTags.option("protein-BEEF")), "Beef")
        waitUntilGone(recipeCard("Stir-fry"), "the stir-fry")
        waitForExists(recipeCard("Chilli"), "the chilli")
        waitForExists(tagged(RecipesTags.FILTER_PROTEIN, "Beef"), "the protein chip saying Beef")
        shot("additions-07-filter-beef")
        tap(hasTestTag(RecipesTags.CLEAR_FILTERS), "Clear filters")
        waitForExists(recipeCard("Stir-fry"), "the stir-fry again")

        tap(hasTestTag(RecipesTags.FILTER_METHOD), "Method")
        tap(hasTestTag(RecipesTags.option("method-OVEN")), "Oven")
        waitUntilGone(recipeCard("Chilli"), "the chilli")
        waitForExists(recipeCard("Traybake"), "the traybake")
        tap(hasTestTag(RecipesTags.CLEAR_FILTERS), "Clear filters")

        tap(hasTestTag(RecipesTags.FILTER_TIME), "Time")
        tap(hasTestTag(RecipesTags.option("time-45")), "45 min or less")
        waitUntilGone(recipeCard("Traybake"), "the traybake")
        waitForExists(recipeCard("Stir-fry"), "the stir-fry, 45 minutes in all")
        shot("additions-08-filter-time")
        tap(hasTestTag(RecipesTags.CLEAR_FILTERS), "Clear filters")

        // Counting the mince as pork puts the chilli under Pork, beside the braise's bacon.
        val mince = ingredientNamed("Lean beef mince (5% fat)")
        goToTab("Ingredients")
        tap(hasTestTag(IngredientsTags.row(mince.id)), "the mince")
        tap(hasTestTag(IngredientEditTags.FOOD_GROUP), "Counts as")
        tapMenuItem("Pork, bacon & ham (protein)")
        tap(hasTestTag(IngredientEditTags.SAVE), "Save")
        waitFor("the mince to be saved as pork") { blocking { appContainer.ingredientRepository.get(mince.id) }?.foodGroup == FoodGroup.PORK }
        goToTab("Recipes")
        tap(hasTestTag(RecipesTags.FILTER_PROTEIN), "Protein")
        tap(hasTestTag(RecipesTags.option("protein-PORK")), "Pork")
        waitForExists(recipeCard("Chilli"), "the chilli under pork")
        waitForExists(recipeCard("Braise"), "the braise under pork")
        waitUntilGone(recipeCard("Stir-fry"), "the stir-fry")
    }

    @Test
    fun aRecipeCopiedFromAChatIsPastedInOneTap() {
        openRecipes(starter = false)
        setClipboard(CHAT_RECIPE)
        waitForText("No recipes yet", timeoutMs = HEAVY_TIMEOUT_MS)
        tapText("Paste a recipe")
        tap(hasTestTag(ReviewTags.PASTE), "Paste")
        waitForText("Spicy Peanut Chicken Noodles", timeoutMs = HEAVY_TIMEOUT_MS)
        shot("additions-09-pasted-review")
        tap(hasTestTag(ReviewTags.SAVE), "Save recipe")
        waitFor("the recipe to be saved", HEAVY_TIMEOUT_MS) { allRecipes().isNotEmpty() }
        val saved = allRecipes().single()
        assertEquals("Spicy Peanut Chicken Noodles", saved.title)
        assertEquals(2, saved.serves)
        assertEquals(5, saved.lines.size)
        assertEquals(listOf("Make the sauce", "Fry the chicken", null), saved.method.map { it.title })
        assertEquals(listOf("For the sauce", "For the sauce", "For the noodles", "For the noodles", "For the noodles"), saved.lines.map { it.group })
    }

    @Test
    fun copyRecipeCopiesThePasteFormatWhichPastesBackAsACopy() {
        openRecipes()
        tap(recipeCard("Stir-fry", "£3.09"), "the stir-fry card")
        waitForText("Chicken & Vegetable Stir-Fry", timeoutMs = HEAVY_TIMEOUT_MS)
        tap(hasTestTag(RecipeDetailTags.MENU), "More options")
        tap(hasTestTag(RecipeDetailTags.COPY), "Copy recipe")
        waitForText("Recipe copied", substring = true)
        assertEquals(RecipeText.format(starterRecipe("Stir-fry")), clipboardText())
        tapBack()

        tapIcon("Add recipe")
        tap(hasTestTag(ReviewTags.PASTE), "Paste")
        waitForExists(hasText("Chicken & Vegetable Stir-Fry"), "the copied recipe in the review", HEAVY_TIMEOUT_MS)
        tap(hasTestTag(ReviewTags.SAVE), "Save recipe")
        waitFor("the copy to be saved", HEAVY_TIMEOUT_MS) { allRecipes().size == 5 }
        val copies = allRecipes().filter { it.title == "Chicken & Vegetable Stir-Fry" }
        assertEquals(2, copies.size)
        assertEquals(copies[0].lines.map { it.text }, copies[1].lines.map { it.text })
        assertTrue(copies.all { it.method.size == 11 })
    }

    private companion object {
        /** A recipe the way a Claude chat writes it, as its copy button puts it on the clipboard. */
        val CHAT_RECIPE = """
            Here's a cheap, high-protein dinner:

            # Spicy Peanut Chicken Noodles

            A quick weeknight stir-fry with a punchy peanut sauce.

            **Serves:** 2 | **Prep:** 10 minutes | **Cook:** 15 minutes

            ## Ingredients

            **For the sauce:**
            - 2 tbsp smooth peanut butter
            - 2 tbsp light soy sauce

            **For the noodles:**
            - 150 g egg noodles
            - 2 chicken breast fillets (about 320 g), sliced
            - 1 tbsp vegetable oil

            ## Method

            1. **Make the sauce (2 min):** Whisk the peanut butter and soy sauce with 3 tbsp hot water.
            2. **Fry the chicken** (5–6 min): Heat the oil in a wok and fry until golden.
            3. Add the noodles, pour over the sauce and toss for 1 minute.

            ---

            Enjoy! Let me know if you'd like a vegetarian version.
        """.trimIndent()
    }
}
