package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.allIngredients
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.ingredientNamed
import io.github.matteopi.budgeting.testing.recipeCard
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.seedStarterBook
import io.github.matteopi.budgeting.testing.starterRecipe
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.food.IngredientsTags
import io.github.matteopi.budgeting.ui.food.ShoppingTags
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientEditTags
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Ingredients tab and the ingredient editor, and the Shopping tab (docs/PLAN.md §11.5, §11.6), on the starter
 * book: a new pack price changes a recipe's cost, portions are kept, an ingredient is archived; the shopping list
 * sums two recipes, ticks, "I have it", free text and *Clear ticked*.
 */
@RunWith(AndroidJUnit4::class)
class IngredientsAndShoppingTest : UiTest() {

    private fun open() {
        seedStarterBook()
        seedOnboardingDone()
        seedRecipesSection()
        launchMain()
    }

    @Test
    fun aNewPackPriceChangesTheRecipesCostAndPortionsAndArchivingWork() {
        open()
        val onion = ingredientNamed("Brown onions")
        goToTab("Ingredients")
        tap(hasTestTag(IngredientsTags.row(onion.id)), "the onions")
        waitForExists(hasTestTag(IngredientEditTags.SAVE), "the ingredient editor", HEAVY_TIMEOUT_MS)
        type("Pack price", "1.50")
        tap(hasTestTag(IngredientEditTags.ADD_PORTION), "Add portion")
        typeInTag(IngredientEditTags.portionName(1), "large")
        typeInTag(IngredientEditTags.portionAmount(1), "200")
        shot("ingredients-01-editor")
        tap(hasTestTag(IngredientEditTags.SAVE), "Save ingredient")
        waitForExists(hasTestTag(IngredientsTags.row(onion.id)), "the Ingredients tab again")

        val saved = blocking { appContainer.ingredientRepository.get(onion.id) }!!
        assertEquals(150L, saved.price?.pence)
        assertTrue(saved.portions.any { it.name == "large" && it.amount == 200.0 })

        // The stir-fry's cost per portion, worked out from what is now saved.
        val ingredients = allIngredients()
        val stirFry = starterRecipe("Stir-fry")
        val expected = RecipeMaths.analyse(
            stirFry,
            ingredients.associateBy { it.id },
            Prices.resolveAll(ingredients, emptyList(), LocalDate.now()),
        ).cost.perPortion
        assertTrue("the onions now cost more", expected.pence > 309)
        goToTab("Recipes")
        waitForExists(recipeCard("Stir-fry", expected.format()), "the stir-fry card at the new price")
        shot("ingredients-02-new-price")

        // A new ingredient, saved, then archived: it leaves the list.
        goToTab("Ingredients")
        tapIcon("Add ingredient")
        type("Name", "Halloumi")
        type("Pack size", "225")
        tap(hasTestTag(IngredientEditTags.SAVE), "Save ingredient")
        waitFor("the halloumi to be saved") { allIngredients().any { it.name == "Halloumi" } }
        val halloumi = allIngredients().first { it.name == "Halloumi" }
        tap(hasTestTag(IngredientsTags.row(halloumi.id)), "the halloumi")
        tap(hasTestTag(IngredientEditTags.ARCHIVE), "Archive")
        tapInDialog("Archive")
        waitUntilGone(hasTestTag(IngredientsTags.row(halloumi.id)), "the archived halloumi")
    }

    @Test
    fun theShoppingListSumsTheRecipesAndTicksOff() {
        seedStarterBook()
        val stirFry = starterRecipe("Stir-fry").id
        val traybake = starterRecipe("Traybake").id
        blocking {
            appContainer.shoppingRepository.addRecipe(stirFry, 2)
            appContainer.shoppingRepository.addRecipe(traybake, 2)
        }
        val onion = ingredientNamed("Brown onions").id
        val chicken = ingredientNamed("Chicken breast fillets").id
        seedOnboardingDone()
        seedRecipesSection()
        launchMain()
        goToTab("Shopping")

        // 150 g of onion in each recipe: one 1 kg bag. 320 g of chicken in each: two 320 g packs.
        waitForExists(tagged(ShoppingTags.lineQuantity(onion), "300 g · 1 pack of 1 kg"), "the onions", HEAVY_TIMEOUT_MS)
        // The header is the list's first row: read it while it is on screen.
        val headerBefore = firstText(hasTestTag(ShoppingTags.HEADER))
        scrollIntoView(hasTestTag(ShoppingTags.lineQuantity(chicken)))
        waitForExists(tagged(ShoppingTags.lineQuantity(chicken), "640 g · 2 packs of 320 g"), "the chicken")
        shot("shopping-01-list")
        val itemsBefore = blocking { appContainer.shoppingRepository.observeEntries().first() }.size
        assertEquals(2, itemsBefore)

        // "I have it" takes the chicken off the bill.
        tap(hasTestTag(ShoppingTags.lineMenu(chicken)), "the chicken's menu")
        tapMenuItem("I have it")
        waitForExists(tagged(ShoppingTags.lineQuantity(chicken), "640 g · you have it"), "the chicken marked")
        scrollIntoView(hasTestTag(ShoppingTags.HEADER))
        waitFor("the header to change from '$headerBefore'") {
            firstText(hasTestTag(ShoppingTags.HEADER)).let { it != null && it != headerBefore }
        }

        // Tick the onions, add a free-text item and tick it, then clear the ticked ones.
        scrollIntoView(hasTestTag(ShoppingTags.lineCheck(onion)))
        tap(hasTestTag(ShoppingTags.lineCheck(onion)), "the onions' tick")
        waitFor("the onions to be ticked") {
            blocking { appContainer.shoppingRepository.observeMarks().first() }.any { it.ingredientId == onion && it.checked }
        }
        tap(hasTestTag(ShoppingTags.ADD_ITEM), "Add item")
        typeInTag(ShoppingTags.ADD_TEXT, "Kitchen roll")
        tap(hasTestTag(ShoppingTags.ADD_CONFIRM), "Add")
        scrollIntoView(txt("Kitchen roll"))
        waitForText("Kitchen roll")
        val roll = blocking { appContainer.shoppingRepository.observeEntries().first() }.first { it.freeText == "Kitchen roll" }
        tap(hasTestTag(ShoppingTags.freeTextCheck(roll.id)), "the kitchen roll's tick")
        shot("shopping-02-ticked")

        tapIcon("More options")
        tapMenuItem("Clear ticked")
        waitForNoText("Kitchen roll")
        waitFor("every mark to be reset") {
            blocking { appContainer.shoppingRepository.observeMarks().first() }.none { it.checked || it.haveIt }
        }
        waitForExists(tagged(ShoppingTags.lineQuantity(chicken), "640 g · 2 packs of 320 g"), "the chicken back on the bill")
        // The recipes on the list stay: their chips are near the top, so bring them back into view.
        val stirFryChip = hasText("Stir-fry · 2 servings", substring = true)
        scrollIntoView(stirFryChip)
        waitForExists(stirFryChip, "the stir-fry still on the list")
        shot("shopping-03-cleared")
    }
}
