package io.github.matteopi.budgeting.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.SeedReceiptItem
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.allIngredients
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.ingredientNamed
import io.github.matteopi.budgeting.testing.recipeCard
import io.github.matteopi.budgeting.testing.seedAccount
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedReceipt
import io.github.matteopi.budgeting.testing.seedRecipesSection
import io.github.matteopi.budgeting.testing.seedStarterBook
import io.github.matteopi.budgeting.testing.starterRecipe
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.ui.food.IngredientsTags
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptMapping
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Receipt prices in recipes (docs/PLAN.md §11.2): once an ingredient is linked to a receipts product, the latest
 * price paid for it wins over the typed pack price, on the Ingredients tab and in every recipe's cost.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptPricesInRecipesTest : UiTest() {

    @Test
    fun aReceiptForTheLinkedProductChangesTheRecipesCost() {
        seedOnboardingDone()
        seedStarterBook()
        val account = seedAccount("Santander current", AccountType.CURRENT, 100_000)
        seedReceipt(
            account,
            "TESCO",
            LocalDate.now().minusDays(1),
            listOf(SeedReceiptItem("TESCO BROWN ONIONS 1KG", "Tesco Brown Onions 1kg", pence = 120)),
        )
        val onion = ingredientNamed("Brown onions")
        val product = blocking { appContainer.receiptRepository.observeProducts().first() }.first { it.name == "Tesco Brown Onions 1kg" }
        blocking { appContainer.ingredientRepository.setProduct(onion.id, product.id) }

        // What the stir-fry costs now: the onions at the £1.20 paid instead of the £0.99 typed.
        val ingredients = allIngredients()
        val purchases = blocking {
            ReceiptMapping.purchasedItems(
                appContainer.receiptRepository.observeReceipts().first(),
                appContainer.receiptRepository.observeAllItems().first(),
            )
        }
        val expected = RecipeMaths.analyse(
            starterRecipe("Stir-fry"),
            ingredients.associateBy { it.id },
            Prices.resolveAll(ingredients, purchases, LocalDate.now()),
        ).cost.perPortion
        assertNotEquals("the receipt price is used", 309L, expected.pence)

        seedRecipesSection()
        launchMain()
        waitForExists(recipeCard("Stir-fry", expected.format()), "the stir-fry at the receipt price", HEAVY_TIMEOUT_MS)
        shot("receipt-prices-01-recipes")

        goToTab("Ingredients")
        waitForExists(tagged(IngredientsTags.row(onion.id), "paid £1.20 at Tesco", substring = true), "the onions' price from the receipt")
        shot("receipt-prices-02-ingredients")
    }
}
