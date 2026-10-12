package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.testing.SeedReceiptItem
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asShortText
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.seedAccount
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedReceipt
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Receipts list and detail (open from Settings, or from a split transaction's edit sheet) and the
 * Insights "Items" card with its item detail. Two receipts are seeded with a milk price that rose
 * from GBP 1.50 to GBP 1.65.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptsTest : UiTest() {

    private val today: LocalDate = LocalDate.now()
    private val tescoDate: LocalDate = today.minusDays(20)
    private val sainsburysDate: LocalDate = today.minusDays(5)

    private val milk = "Semi-skimmed milk 2 pints"
    private val bread = "White bread"

    /** "20 Sep 2026", how the receipt screens write a date. */
    private fun LocalDate.withYear() = "${asShortText()} $year"

    private fun seedAccountOnly(): Long {
        seedOnboardingDone()
        return seedAccount("Current", AccountType.CURRENT, 100_000)
    }

    /**
     * Tesco, 20 days ago: milk 1.50, bread 1.20 (both Groceries) and a USB cable 5.00 (Shopping, not a
     * known product) = GBP 7.70, split into Groceries 2.70 and Shopping 5.00.
     * Sainsbury's, 5 days ago: milk 1.65 and two loaves at 1.20 = GBP 4.05, all Groceries.
     */
    private fun seedShops() {
        val account = seedAccountOnly()
        seedReceipt(
            account, "TESCO", tescoDate,
            listOf(
                SeedReceiptItem("TS SEMI SKMD MLK 2PT", milk, 150),
                SeedReceiptItem("TS WHITE LOAF", bread, 120),
                SeedReceiptItem("USB CABLE", null, 500, category = "Shopping"),
            ),
        )
        seedReceipt(
            account, "SAINSBURYS", sainsburysDate,
            listOf(
                SeedReceiptItem("SAINSBURYS SEMI SKIMMED 2PT", milk, 165),
                SeedReceiptItem("WHITE LOAF", bread, 240, quantity = 2, unitPence = 120),
            ),
        )
    }

    private fun receiptRow(store: String, total: Long) = hasText(store) and hasText(gbp(total)) and hasClickAction()

    private fun openReceiptsFromSettings() {
        launchHome()
        openSettings()
        tapText("Receipts")
        waitForExists(hasText("Receipts"), "the Receipts title")
    }

    private fun openTescoReceipt() {
        openReceiptsFromSettings()
        tap(receiptRow("Tesco", 770), "the Tesco receipt")
        waitForText("Items")
    }

    private fun transactionRow(label: String, pence: Long) = hasText(label) and hasText(gbp(pence)) and hasClickAction()

    /** Swipes Insights to its last card and waits for the Items card. */
    private fun scrollToItemsCard() {
        waitForText("Weekly spending")
        swipeListUp(6)
        waitForText("Biggest price changes")
    }

    // ----- list -----

    @Test
    fun receiptsListShowsEachReceiptNewestFirst() {
        seedShops()
        openReceiptsFromSettings()

        waitForExists(receiptRow("Sainsbury's", 405), "the Sainsbury's receipt row")
        waitForExists(receiptRow("Tesco", 770), "the Tesco receipt row")
        waitForText("${sainsburysDate.withYear()} · 2 items")
        waitForText("${tescoDate.withYear()} · 3 items")
        assertTrue(
            "newest receipt first",
            topOf(receiptRow("Sainsbury's", 405)) < topOf(receiptRow("Tesco", 770)),
        )
        shot("receipts-01-list")
    }

    @Test
    fun emptyReceiptsListExplainsHowToScanOne() {
        seedAccountOnly()
        openReceiptsFromSettings()
        waitForText("No receipts yet", substring = true)
        waitForText("Scan receipt", substring = true)
        shot("receipts-02-empty")
    }

    // ----- detail -----

    @Test
    fun receiptDetailShowsHeaderItemsAndTheTransactionsItWasSplitInto() {
        seedShops()
        openTescoReceipt()

        // Header.
        waitForText(gbp(770))
        waitForText(tescoDate.withYear())
        waitForText("Paid from Current")

        // Items: the product name when linked, the printed line when not; prices and categories.
        waitForText(milk)
        waitForText(bread)
        waitForText("USB CABLE")
        waitForText(gbp(150))
        waitForText(gbp(120))
        waitForText("Shopping")
        waitForExists(hasText("Groceries"), "the Groceries category on an item")
        shot("receipts-03-detail")

        // The two transactions the shop was split into.
        waitForText("Transactions")
        waitForExists(hasText("Tesco · Groceries"), "the Groceries part")
        waitForText(gbp(270))
        waitForExists(hasText("Tesco · Shopping"), "the Shopping part")
        shot("receipts-04-detail-splits")
    }

    @Test
    fun deletingMergesTheSplitBackIntoOneTransactionByDefault() {
        seedShops()
        openTescoReceipt()

        tapText("Delete receipt")
        waitForText("Delete receipt?")
        waitForText("Merge back into one transaction")
        waitForText("Keep the split transactions")
        shot("receipts-05-delete-dialog")
        tapInDialog("Delete")

        // Back on the list: only the Sainsbury's receipt is left.
        waitForExists(receiptRow("Sainsbury's", 405), "the Sainsbury's receipt row")
        waitUntilGone(receiptRow("Tesco", 770), "the deleted Tesco receipt")
        assertEquals(1, blocking { appContainer.receiptRepository.observeReceipts().first().size })

        // One transaction with the whole total, no leftovers.
        goToTab("Activity")
        waitForExists(transactionRow("Tesco", -770), "the merged Tesco transaction")
        val tescoTransactions = blocking { appContainer.transactionRepository.observeAll().first() }
            .filter { it.label.startsWith("Tesco") }
        assertEquals(1, tescoTransactions.size)
        assertEquals(-770L, tescoTransactions.single().amount.pence)
        assertNull(tescoTransactions.single().receiptId)
        shot("receipts-06-merged-in-activity")
    }

    @Test
    fun deletingCanKeepTheSplitTransactions() {
        seedShops()
        openTescoReceipt()

        tapText("Delete receipt")
        waitForText("Keep the split transactions")
        tapText("Keep the split transactions")
        tapInDialog("Delete")
        waitUntilGone(receiptRow("Tesco", 770), "the deleted Tesco receipt")

        goToTab("Activity")
        waitForExists(transactionRow("Tesco · Groceries", -270), "the Groceries part")
        waitForExists(transactionRow("Tesco · Shopping", -500), "the Shopping part")
        shot("receipts-07-kept-in-activity")
    }

    @Test
    fun aSplitTransactionOpensItsReceiptFromTheEditSheet() {
        seedShops()
        launchHome()
        goToTab("Activity")
        tap(transactionRow("Sainsbury's · Groceries", -405), "the Sainsbury's transaction")
        waitForText("Edit transaction")
        shot("receipts-08-edit-sheet-view-receipt")
        tapText("View receipt")

        waitUntilGone(txt("Edit transaction"), "the edit sheet")
        waitForText("Paid from Current")
        waitForText(sainsburysDate.withYear())
        waitForText(bread)
        waitForText("2 × ${gbp(120)}", substring = true)
        tapBack()
        waitForExists(transactionRow("Sainsbury's · Groceries", -405), "Activity after going back")
    }

    // ----- insights -----

    @Test
    fun insightsItemsCardShowsMostBoughtAndPriceChanges() {
        seedShops()
        launchHome()
        goToTab("Insights")
        scrollToItemsCard()
        waitForText("Items")
        waitForText("Most bought")

        // By spend: bread 1.20 + 2.40 = 3.60 (3 bought) ahead of milk 1.50 + 1.65 = 3.15 (2 bought).
        waitForExists(hasText(bread) and hasText("3 bought · ${gbp(360)}") and hasClickAction(), "bread in Most bought")
        waitForExists(hasText(milk) and hasText("2 bought · ${gbp(315)}") and hasClickAction(), "milk in Most bought")
        assertTrue(
            "bread first",
            topOf(hasText("3 bought · ${gbp(360)}")) < topOf(hasText("2 bought · ${gbp(315)}")),
        )

        // Milk went from 1.50 to 1.65 (+10%); bread stayed at 1.20 so it is not a change.
        waitForExists(
            hasText(milk) and hasText("↑ 10.0%") and hasText("${gbp(150)} → ${gbp(165)}") and hasClickAction(),
            "the milk price change",
        )
        assertEquals("only milk changed price", 1, count(hasText("→", substring = true)))
        shot("receipts-09-insights-items")

        // The period chips apply to the card: a longer period still contains both shops.
        tapText("12 months")
        waitForExists(hasText(milk) and hasText("2 bought · ${gbp(315)}"), "milk with the 12 months period")
    }

    @Test
    fun insightsItemsCardIsHiddenUntilThereIsAReceipt() {
        seedAccountOnly()
        launchHome()
        goToTab("Insights")
        waitForText("Weekly spending")
        swipeListUp(6)
        waitForText("Income vs. spending")
        assertNeverShows(txt("Biggest price changes"), "the Items card without receipts")
        assertEquals(0, count(txt("Items")))
    }

    @Test
    fun searchingAnItemOpensItsPriceHistory() {
        seedShops()
        launchHome()
        goToTab("Insights")
        scrollToItemsCard()

        type("Search items", "MILK")
        tap(clickableText(milk), "the milk result")

        // Item detail: what it cost over the period and every unit price, newest first.
        waitForText("Price history")
        waitForText("Spent over 12 weeks")
        waitForText(gbp(315))
        waitForText("2 bought in 2 purchases")
        waitForText("↑ 10.0% since ${gbp(150)}")
        waitForExists(hasText(sainsburysDate.withYear()), "the Sainsbury's purchase date")
        waitForExists(hasText(tescoDate.withYear()), "the Tesco purchase date")
        waitForText("Sainsbury's")
        waitForText("Tesco")
        waitForText(gbp(165))
        assertTrue(
            "newest purchase first",
            topOf(hasText(sainsburysDate.withYear())) < topOf(hasText(tescoDate.withYear())),
        )
        shot("receipts-10-item-detail")

        tapBack()
        waitForText("Biggest price changes")
    }

    @Test
    fun allItemsListsEveryBoughtProduct() {
        seedShops()
        launchHome()
        goToTab("Insights")
        scrollToItemsCard()

        tapText("All items (2)")
        // Bread is in Most bought already; the expanded list adds a second tappable row for it.
        waitFor("bread in the all-items list too") { count(clickableText(bread)) >= 2 }
        tap(clickableText(bread), "bread")
        waitForText("Price history")
        waitForText("3 bought in 2 purchases")
        waitForText(gbp(360))
    }
}
