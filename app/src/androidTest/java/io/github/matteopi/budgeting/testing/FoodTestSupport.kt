package io.github.matteopi.budgeting.testing

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.data.settings.AppSection
import kotlinx.coroutines.flow.first

/*
 * Seeds and helpers for the Recipes section's device tests (docs/PLAN.md §11). The starter book's numbers are
 * the §11.7 checkpoint ones: stir-fry £3.09 / 804 kcal, traybake £3.83 (£3.05 without optional extras),
 * braise £3.72, chilli £2.54 / 897 kcal.
 */

/** Imports the starter recipe book through the repository, as *Add the starter recipes* does. */
fun seedStarterBook(): List<Long> = blocking { appContainer.recipeRepository.importBundle(RecipeBundle.starter()).recipeIds }

/**
 * The app opens in the Recipes section, as if the user had last been there. Recipe timers stay inside the app: a
 * test must not hand timers to the emulator's clock app (docs/PLAN.md §11.11).
 */
fun seedRecipesSection() = blocking {
    appContainer.settingsStore.setAppSection(AppSection.RECIPES)
    appContainer.settingsStore.setPhoneTimers(false)
}

/** Puts [text] on the clipboard, as a copy button in another app would. */
fun setClipboard(text: String) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
    testApp.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("recipe", text))
}

/** The text on the clipboard (read while the app is in front, as Android requires). */
fun clipboardText(): String? {
    var text: String? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        text = testApp.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
    }
    return text
}

fun allRecipes(): List<Recipe> = blocking { appContainer.recipeRepository.observeAll().first() }

fun allIngredients(): List<Ingredient> = blocking { appContainer.ingredientRepository.observeAll().first() }

/** The saved recipe with this short title ("Stir-fry", "Traybake", "Braise", "Chilli"). */
fun starterRecipe(shortTitle: String): Recipe = allRecipes().first { it.shortTitle == shortTitle }

fun ingredientNamed(name: String): Ingredient = allIngredients().first { it.name == name }

/** A recipe card on the Recipes tab showing all of [texts] (its title, cost, kcal…). */
fun recipeCard(vararg texts: String): SemanticsMatcher =
    texts.fold(hasClickAction()) { matcher, text -> matcher and hasText(text) }

/**
 * The node with test tag [tag] that shows [text], whether the text is on the node itself (a merged button or chip)
 * or on a child of it (a plain row or surface).
 */
fun tagged(tag: String, text: String, substring: Boolean = false): SemanticsMatcher =
    hasTestTag(tag) and (hasText(text, substring = substring) or hasAnyDescendant(hasText(text, substring = substring)))

/** Replaces the text of the field with this test tag, scrolling to it first, then hides the keyboard. */
fun UiTest.typeInTag(tag: String, value: String) {
    val matcher = hasTestTag(tag)
    waitForExists(matcher, "the field '$tag'")
    scrollIntoView(matcher)
    compose.onAllNodes(matcher)[0].performTextReplacement(value)
    compose.waitForIdle()
    hideKeyboard()
}
