package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.recipeCard
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.seedStarterBook
import io.github.matteopi.budgeting.testing.starterRecipe
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.food.recipe.RecipeDetailTags
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Recipes tab and the recipe screen (docs/PLAN.md §11.8) on the starter book: adding it from the empty state,
 * the cards' numbers, the recipe screen's header numbers, cue boxes and storage, scaling, the optional-extras switch,
 * and the rating and notes being kept.
 */
@RunWith(AndroidJUnit4::class)
class RecipesViewTest : UiTest() {

    private fun openRecipes() {
        seedOnboardingDone()
        seedRecipesSection()
        launchMain()
    }

    @Test
    fun theStarterRecipesAreAddedFromTheEmptyStateWithTheirNumbers() {
        openRecipes()
        waitForText("No recipes yet", timeoutMs = HEAVY_TIMEOUT_MS)
        shot("recipes-01-empty")

        tapText("Add the starter recipes")
        waitForExists(recipeCard("Stir-fry", "£3.09", "804 kcal"), "the stir-fry card", HEAVY_TIMEOUT_MS)
        waitForExists(recipeCard("Chilli", "£2.54", "897 kcal"), "the chilli card")
        waitForExists(recipeCard("Traybake", "£3.83"), "the traybake card")
        waitForExists(recipeCard("Braise", "£3.72"), "the braise card")
        assertEquals(4, allRecipeCount())
        shot("recipes-02-starter-cards")
    }

    @Test
    fun theRecipeScreenShowsTheNumbersCuesAndStorageAndScales() {
        seedStarterBook()
        openRecipes()
        tap(recipeCard("Chilli", "£2.54"), "the chilli card")
        waitForText("Beef, Bean & Lentil Chilli", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForExists(tagged(RecipeDetailTags.COST, "£2.54 a portion"), "the cost per portion")
        waitForExists(tagged(RecipeDetailTags.KCAL, "897 kcal"), "the kcal")
        shot("recipes-03-chilli")

        // Step 2's cue and the storage section.
        val cue = hasText("Ready when: the bubbling turns to a sizzle", substring = true)
        scrollIntoView(cue)
        waitForExists(cue, "the cue box of step 2")
        shot("recipes-04-method")
        scrollIntoView(txt("Freezing and storing"))
        waitForText("Freezing and storing")
        waitForText("Leave a 1–2 cm gap at the top: liquid expands as it freezes.")

        // Six servings instead of four: the mince line is rewritten, a portion costs the same.
        scrollIntoView(hasContentDescription("More servings"))
        tapIcon("More servings")
        tapIcon("More servings")
        scrollIntoView(hasTestTag(RecipeDetailTags.line(0)))
        waitForExists(tagged(RecipeDetailTags.line(0), "750 g lean beef mince (5% fat)"), "the scaled mince line")
        scrollIntoView(hasTestTag(RecipeDetailTags.COST))
        waitForExists(tagged(RecipeDetailTags.COST, "£2.54 a portion"), "the same cost per portion")
        shot("recipes-05-six-servings")
    }

    @Test
    fun optionalExtrasCanBeLeftOutAndTheRatingAndNotesAreKept() {
        seedStarterBook()
        openRecipes()
        tap(recipeCard("Traybake", "£3.83"), "the traybake card")
        waitForExists(tagged(RecipeDetailTags.COST, "£3.83 a portion"), "the traybake's cost", HEAVY_TIMEOUT_MS)
        tap(hasTestTag(RecipeDetailTags.OPTIONAL_SWITCH), "the optional extras switch")
        waitForExists(tagged(RecipeDetailTags.COST, "£3.05 a portion"), "the cost without the extras")
        assertEquals(false, blocking { appContainer.settingsStore.countOptionalExtras.first() })
        tap(hasTestTag(RecipeDetailTags.OPTIONAL_SWITCH), "the optional extras switch again")
        waitForExists(tagged(RecipeDetailTags.COST, "£3.83 a portion"), "the cost with the extras")
        tapBack()

        // Rate the stir-fry and write a note; both are there when it is opened again.
        tap(recipeCard("Stir-fry", "£3.09"), "the stir-fry card")
        waitForText("Chicken & Vegetable Stir-Fry", timeoutMs = HEAVY_TIMEOUT_MS)
        scrollIntoView(hasContentDescription("Rate 4 stars"))
        tapIcon("Rate 4 stars")
        waitForText("Rated 4 out of 5")
        typeInTag(RecipeDetailTags.NOTES, "Less soy next time")
        val stirFryId = starterRecipe("Stir-fry").id
        waitFor("the note to be saved") {
            blocking { appContainer.recipeRepository.get(stirFryId) }?.notes == "Less soy next time"
        }
        shot("recipes-06-rated")
        tapBack()
        waitForExists(recipeCard("Stir-fry") and hasContentDescription("Rated 4 out of 5"), "the stir-fry card with its rating")
        tap(recipeCard("Stir-fry", "£3.09"), "the stir-fry card again")
        scrollIntoView(hasTestTag(RecipeDetailTags.NOTES))
        waitForExists(tagged(RecipeDetailTags.NOTES, "Less soy next time"), "the note kept")
        waitForText("Rated 4 out of 5")
    }

    private fun allRecipeCount(): Int = blocking { appContainer.recipeRepository.observeAll().first() }.count { !it.archived }
}
