package io.github.matteopi.budgeting.ui

import android.view.WindowManager
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.recipeCard
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.seedStarterBook
import io.github.matteopi.budgeting.testing.starterRecipe
import io.github.matteopi.budgeting.ui.food.CompareTags
import io.github.matteopi.budgeting.ui.food.recipe.CookingTags
import io.github.matteopi.budgeting.ui.food.recipe.RecipeDetailTags
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The cooking view's ticks and kept-on screen, and Compare's sorting (docs/PLAN.md §11.8). */
@RunWith(AndroidJUnit4::class)
class CookingAndCompareTest : UiTest() {

    private fun open(): ActivityScenario<MainActivity> {
        seedStarterBook()
        seedOnboardingDone()
        seedRecipesSection()
        return launchMain()
    }

    private fun ticks(recipeId: Long): Set<String> = blocking { appContainer.settingsStore.observeCookingTicks(recipeId).first() }

    private fun keepsScreenOn(scenario: ActivityScenario<MainActivity>): Boolean {
        var on = false
        scenario.onActivity { activity ->
            on = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        }
        return on
    }

    @Test
    fun cookingTicksAreKeptUntilDoneAndTheScreenStaysOnMeanwhile() {
        val scenario = open()
        val stirFry = starterRecipe("Stir-fry").id
        tap(recipeCard("Stir-fry", "£3.09"), "the stir-fry card")
        waitForExists(hasTestTag(RecipeDetailTags.COOK), "the Cook button", HEAVY_TIMEOUT_MS)
        assertFalse("the screen isn't kept on outside the cooking view", keepsScreenOn(scenario))
        tap(hasTestTag(RecipeDetailTags.COOK), "Cook")

        waitForExists(hasTestTag(CookingTags.line(0)), "the first ingredient")
        waitFor("the screen to be kept on") { keepsScreenOn(scenario) }
        tap(hasTestTag(CookingTags.line(0)), "the first ingredient")
        tap(hasTestTag(CookingTags.line(1)), "the second ingredient")
        tap(hasTestTag(CookingTags.step(0)), "the first step")
        waitFor("three ticks to be saved") { ticks(stirFry) == setOf("line-0", "line-1", "step-0") }
        shot("cooking-01-ticked")

        // Leaving and coming back keeps the ticks; the screen is no longer kept on in between.
        tapBack()
        waitForExists(hasTestTag(RecipeDetailTags.COOK), "the recipe screen")
        waitFor("the screen to be let go") { !keepsScreenOn(scenario) }
        tap(hasTestTag(RecipeDetailTags.COOK), "Cook again")
        waitForExists(hasTestTag(CookingTags.line(0)), "the first ingredient again")
        assertEquals(setOf("line-0", "line-1", "step-0"), ticks(stirFry))

        // Done cooking offers the kitchen (docs/PLAN.md §14.5); Not now forgets the ticks and goes back to the recipe.
        tapText("Done cooking", index = 0)
        tapInDialog("Not now")
        waitForExists(hasTestTag(RecipeDetailTags.COOK), "the recipe screen after Done cooking")
        waitFor("the ticks to be cleared") { ticks(stirFry).isEmpty() }
        assertTrue(!keepsScreenOn(scenario))
    }

    @Test
    fun compareSortsByAnyColumn() {
        open()
        val chilli = starterRecipe("Chilli").id
        val stirFry = starterRecipe("Stir-fry").id
        val braise = starterRecipe("Braise").id
        val traybake = starterRecipe("Traybake").id
        // Compare is on the Recipes tab's top bar since the diary took its tab (docs/PLAN.md §14.1).
        tapIcon("Compare recipes")
        waitForExists(hasTestTag(CompareTags.row(chilli)), "the chilli row", HEAVY_TIMEOUT_MS)
        shot("compare-01-table")

        tapText("Cost")
        waitFor("the cheapest first") {
            val order = listOf(chilli, stirFry, braise, traybake).map { topOf(hasTestTag(CompareTags.row(it))) }
            order == order.sorted()
        }
        shot("compare-02-by-cost")

        tapText("Protein")
        waitFor("the most protein first") {
            topOf(hasTestTag(CompareTags.row(braise))) < topOf(hasTestTag(CompareTags.row(stirFry))) &&
                topOf(hasTestTag(CompareTags.row(stirFry))) < topOf(hasTestTag(CompareTags.row(chilli)))
        }
        shot("compare-03-by-protein")
    }
}
