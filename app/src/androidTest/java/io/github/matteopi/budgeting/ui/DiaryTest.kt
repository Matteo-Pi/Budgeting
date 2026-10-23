package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.recipeCard
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.seedStarterBook
import io.github.matteopi.budgeting.testing.starterRecipe
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.food.diary.DiaryTags
import io.github.matteopi.budgeting.ui.food.recipe.RecipeDetailTags
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The food diary (docs/PLAN.md §14.5): the chilli cooked into 4 fridge and 2 freezer portions, a portion eaten with
 * rice on the side, *Ate one* bringing the rice again, three slices of toast and a quick entry read into the diary,
 * the summary's numbers, all nutrients, and deleting with Undo.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiaryTest : UiTest() {

    private fun open() {
        seedStarterBook()
        seedOnboardingDone()
        seedRecipesSection()
        launchMain()
    }

    private fun batches() = blocking { appContainer.diaryRepository.observeBatches().first() }

    private fun todayRows() = blocking { appContainer.diaryRepository.observeDay(appContainer.clock.today()).first() }

    /** "1,234 kcal" the way the diary writes it. */
    private fun kcal(value: Double): String = "${Math.round(value)} kcal"

    @Test
    fun theChilliIsCookedIntoTheKitchenAndEatenWithRice() {
        open()
        tap(recipeCard("Chilli"), "the chilli card")
        waitForExists(hasTestTag(RecipeDetailTags.COOKED), "I cooked this", HEAVY_TIMEOUT_MS)
        tap(hasTestTag(RecipeDetailTags.COOKED), "I cooked this")

        // 4 servings cooked, split into 6 portions, 2 of them frozen.
        waitForExists(hasTestTag(DiaryTags.BATCH_SHEET), "the batch sheet")
        tapIcon("More portions made")
        tapIcon("More portions made")
        tapIcon("More portions frozen")
        tapIcon("More portions frozen")
        waitForText("4 in the fridge · 2 in the freezer")
        shot("diary-01-batch-sheet")
        tap(hasTestTag(DiaryTags.BATCH_SAVE), "Save to the kitchen")
        waitFor("the batch to be saved") { batches().size == 1 }
        val batch = batches().single()
        assertEquals(4.0, batch.fridgeLeft, 1e-9)
        assertEquals(2.0, batch.freezerLeft, 1e-9)
        assertEquals(starterRecipe("Chilli").id, batch.recipeId)
        tapBack()

        goToTab("Kitchen")
        waitForExists(tagged(DiaryTags.batch(batch.id), "4 portions", substring = true), "the fridge's chilli")
        waitForExists(tagged(DiaryTags.frozen(batch.id), "2 portions", substring = true), "the freezer's chilli")
        shot("diary-02-kitchen")

        // From the diary: the batch's name opens Log food on it; rice goes on the side.
        goToTab("Diary")
        waitForExists(hasTestTag(DiaryTags.ready(batch.id)), "Ready to eat")
        tap(hasText("Chilli") and hasAnyAncestor(hasTestTag(DiaryTags.ready(batch.id))) and hasClickAction(), "the chilli's name")
        waitForText("On the side")
        tapText("Add a side")
        typeInTag(DiaryTags.SEARCH, "long-grain")
        val rice = blocking { appContainer.ingredientRepository.observeAll().first() }.first { it.name == "Long-grain white rice" }
        tap(hasTestTag(DiaryTags.side("i${rice.id}")), "the rice")
        waitForText("75 g")
        shot("diary-03-log-with-rice")
        tap(hasTestTag(DiaryTags.LOG_SAVE), "Save")

        waitFor("the portion and its rice") { todayRows().size == 2 }
        val rows = todayRows()
        val portion = rows.single { it.sideOf == null }
        assertEquals(portion.id, rows.single { it.sideOf != null }.sideOf)
        assertEquals(3.0, batches().single().fridgeLeft, 1e-9)
        val expected = portion.intake.kcal + 270.0
        waitForExists(tagged(DiaryTags.KCAL, kcal(expected)), "the day's kcal")
        waitForExists(tagged(DiaryTags.row(portion.id), "Chilli", substring = true), "the chilli row")
        shot("diary-04-day")

        // Ate one brings the rice it was last eaten with.
        tap(hasText("Ate one") and hasAnyAncestor(hasTestTag(DiaryTags.ready(batch.id))), "Ate one")
        waitForText("Logged Chilli + rice", substring = true)
        waitFor("a second portion with its rice") { todayRows().size == 4 }
        assertEquals(2.0, batches().single().fridgeLeft, 1e-9)
        waitForExists(tagged(DiaryTags.KCAL, kcal(expected * 2)), "two portions with rice")
    }

    @Test
    fun toastAndAQuickEntryGoIntoTheDiaryAndCanBeDeletedAndUndone() {
        open()
        goToTab("Diary")
        tapIcon("Log food")
        tapText("Quick")
        typeInTag(DiaryTags.QUICK_TEXT, "3 slices of toast")
        waitForText("White bread")
        waitForText("3 slices", substring = true)
        tapText("Use this")
        shot("diary-05-toast")
        tap(hasTestTag(DiaryTags.LOG_SAVE), "Save")
        waitFor("the toast to be logged") { todayRows().size == 1 }
        val toast = todayRows().single()
        assertEquals("White bread", toast.name)
        assertEquals(120.0, toast.amount, 1e-9)
        assertEquals("3 slices", toast.amountText)
        val breadKcal = FoodDatabase.byKey("bread_white")!!.ingredient.per100.kcal!! * 1.2
        assertEquals(breadKcal, toast.intake.kcal, 1e-6)
        assertTrue("the bread was added to the ingredients", toast.ingredientId != null)

        // A typed entry.
        tapIcon("Log food")
        tapText("Quick")
        typeInTag(DiaryTags.QUICK_NAME, "Cereal bar")
        typeInTag(DiaryTags.QUICK_KCAL, "190")
        typeInTag(DiaryTags.QUICK_PROTEIN, "3")
        tap(hasTestTag(DiaryTags.LOG_SAVE), "Save")
        waitFor("the cereal bar to be logged") { todayRows().size == 2 }
        waitForExists(tagged(DiaryTags.KCAL, kcal(breadKcal + 190)), "the day's kcal")
        shot("diary-06-day")

        // All nutrients: the bread's calcium, and "at least" where the cereal bar didn't say.
        tap(hasTestTag(DiaryTags.ALL_NUTRIENTS), "All nutrients")
        waitForText("All nutrients")
        waitForExists(hasTestTag(DiaryTags.nutrient("CALCIUM")), "calcium")
        scrollIntoView(hasTestTag(DiaryTags.nutrient("IRON")))
        waitForExists(tagged(DiaryTags.nutrient("IRON"), "at least", substring = true), "iron is at least")
        shot("diary-07-all-nutrients")
        tapBack()

        // Delete the toast, then Undo.
        tap(hasTestTag(DiaryTags.row(toast.id)), "the toast row")
        tapInDialog("Delete")
        waitFor("the toast to be deleted") { todayRows().size == 1 }
        tapText("Undo")
        waitFor("the toast to be back") { todayRows().any { it.id == toast.id } }
    }

    @Test
    fun typingAndDeletingInTheSearchKeepsEveryKey() {
        open()
        goToTab("Diary")
        tapIcon("Log food")
        tapText("Foods")
        val search = hasTestTag(DiaryTags.SEARCH)
        waitForExists(search, "the search")
        // One key at a time, as a keyboard sends them: each search runs while the next key arrives.
        for (letter in "chicken breast") compose.onNode(search).performTextInput(letter.toString())
        waitForExists(search and hasText("chicken breast"), "every letter typed")
        repeat(7) { compose.onNode(search).performKeyInput { pressKey(Key.Backspace) } }
        waitForExists(search and hasText("chicken"), "every backspace")
        waitForExists(hasText("Chicken breast fillets"), "a result for it")
    }

    @Test
    fun waterAddsAGlassAtATime() {
        open()
        goToTab("Diary")
        tap(hasTestTag(DiaryTags.ADD_GLASS), "+ glass")
        tap(hasTestTag(DiaryTags.ADD_GLASS), "+ glass")
        waitForExists(tagged(DiaryTags.WATER, "Water 500 of 2000 ml"), "two glasses")
        tapText("− glass")
        waitForExists(tagged(DiaryTags.WATER, "Water 250 of 2000 ml"), "one glass")
    }
}
