package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.seedStarterBook
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.ui.food.diary.DiaryTags
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Kitchen tab (docs/PLAN.md §14.5): *I cooked* the stir-fry, freeze a portion, throw one away and undo it, then
 * eat the frozen one.
 */
@RunWith(AndroidJUnit4::class)
class KitchenTest : UiTest() {

    private fun batch() = blocking { appContainer.diaryRepository.observeBatches().first().single() }

    private fun inCard(tag: String, text: String) = hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(tag))

    @Test
    fun aBatchIsCookedFrozenThrownAwayAndEaten() {
        seedStarterBook()
        seedOnboardingDone()
        seedRecipesSection()
        launchMain()
        goToTab("Kitchen")
        waitForText("Nothing cooked yet", substring = true)
        tapIcon("I cooked")
        tapInDialog("Chicken & Vegetable Stir-Fry")
        tap(hasTestTag(DiaryTags.BATCH_SAVE), "Save to the kitchen")
        waitFor("the batch") { blocking { appContainer.diaryRepository.observeBatches().first() }.size == 1 }
        val id = batch().id
        waitForExists(tagged(DiaryTags.batch(id), "2 portions", substring = true), "two portions in the fridge")
        shot("kitchen-01-cooked")

        // Freeze one.
        tap(inCard(DiaryTags.batch(id), "Freeze"), "Freeze")
        tapInDialog("Move")
        waitForExists(tagged(DiaryTags.frozen(id), "1 portion", substring = true), "one in the freezer")
        assertEquals(1.0, batch().fridgeLeft, 1e-9)
        assertEquals(1.0, batch().freezerLeft, 1e-9)

        // Throw away the fridge's, then Undo.
        tap(inCard(DiaryTags.batch(id), "Throw away"), "Throw away")
        tapInDialog("Throw away")
        waitFor("the portion to be thrown away") { batch().wasted == 1.0 }
        waitUntilGone(hasTestTag(DiaryTags.batch(id)), "the fridge card")
        tapText("Undo")
        waitFor("it to be back") { batch().fridgeLeft == 1.0 && batch().wasted == 0.0 }
        shot("kitchen-02-frozen")

        // Eat the frozen one.
        tap(inCard(DiaryTags.frozen(id), "Ate one"), "Ate one from the freezer")
        waitFor("the frozen portion to be eaten") { batch().freezerLeft == 0.0 }
        val rows = blocking { appContainer.diaryRepository.observeDay(appContainer.clock.today()).first() }
        assertEquals(listOf("Stir-fry"), rows.map { it.name })
        assertEquals(1.0, batch().fridgeLeft, 1e-9)
    }
}
