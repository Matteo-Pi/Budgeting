package io.github.matteopi.budgeting.ui

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.receipts.ProductNames
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.ReceiptTestData
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asFieldText
import io.github.matteopi.budgeting.testing.asHeaderText
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.seedAccount
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedTransaction
import io.github.matteopi.budgeting.testing.testApp
import io.github.matteopi.budgeting.ui.receipts.ReceiptReviewTags
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The receipt flow end to end, entering through the Android share target with a shared text receipt
 * (so no camera or OCR is involved): review, correct, categorise, save, and see the split in Activity.
 * Item fields are found by the tags `receipt-item-<key>-<field>` (keys count from 1 in reading
 * order), never by position in the lazy list.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptReviewTest : UiTest() {

    private val today: LocalDate = LocalDate.now()
    private val receiptDate: LocalDate = today.minusDays(1)

    private fun sharedText(text: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setClass(testApp, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)

    private fun itemTag(key: Long, field: String): SemanticsMatcher = hasTestTag(ReceiptReviewTags.item(key, field))

    private fun saveButton(): SemanticsMatcher = hasTestTag(ReceiptReviewTags.SAVE)

    private fun row(title: String, amount: String) = hasText(title) and hasText(amount) and hasClickAction()

    /** Replaces the text of the tagged field (scrolling the lazy list to it first). */
    private fun typeInto(matcher: SemanticsMatcher, what: String, value: String) {
        waitForExists(matcher, what)
        scrollIntoView(matcher)
        compose.onAllNodes(matcher)[0].performTextReplacement(value)
        compose.waitForIdle()
    }

    private fun valueOf(matcher: SemanticsMatcher): String? = runCatching {
        compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    }.getOrNull()

    private fun assertValue(matcher: SemanticsMatcher, what: String, expected: String) {
        waitFor("$what to read '$expected' (it reads '${valueOf(matcher)}')") { valueOf(matcher) == expected }
    }

    /** Opens a drop-down item picker (a category) and chooses [option]. */
    private fun pickCategory(key: Long, option: String) {
        tap(itemTag(key, "category"), "the category picker of item $key")
        tapMenuItem(option)
        waitUntilGone(isPopup(), "the category menu")
        waitForExists(
            itemTag(key, "category") and hasAnyDescendant(hasText(option)),
            "item $key to show category '$option'",
        )
    }

    /** Seeds one account and the hand-entered Tesco shop the receipt should replace; returns the account id. */
    private fun seedShop(): Long {
        seedOnboardingDone()
        val account = seedAccount("Lloyds current", AccountType.CURRENT, 100_000)
        seedTransaction(account, -ReceiptTestData.totalPence, receiptDate, "Groceries", "Tesco")
        return account
    }

    private fun openSharedTescoReceipt() {
        launchMain(sharedText(ReceiptTestData.text(receiptDate)))
        waitForExists(txt("Review receipt"), "the review screen", HEAVY_TIMEOUT_MS)
        waitForExists(itemTag(1, "name"), "the first item")
    }

    private val replacesText get() = "Replaces Tesco -£16.64 on ${receiptDate.asHeaderText()}"

    @Test
    fun sharedReceiptIsReviewedCorrectedCategorisedAndSavedAsSplitTransactions() {
        val account = seedShop()
        openSharedTescoReceipt()

        // What was read: store, date, total, account, items, and that it replaces the hand-entered shop.
        assertFieldValue("Store", "Tesco")
        assertFieldValue("Date", receiptDate.asFieldText())
        assertFieldValue("Total", "16.64")
        assertFieldValue("Account", "Lloyds current")
        assertValue(itemTag(1, "name"), "item 1's name", "HOVIS MEDIUM SLICED 800G")
        assertValue(itemTag(1, "price"), "item 1's price", "1.30")
        assertValue(itemTag(10, "name"), "item 10's name", "SATSUMAS EASY PEEL")
        waitForText("Items add up to the total ✓")
        waitForText(replacesText)
        shot("receipt-review-01-loaded")

        // A wrong price: the check says by how much; an unreadable price blocks saving until fixed.
        typeInto(itemTag(1, "price"), "item 1's price", "1.40")
        waitForText("Items are £0.10 more than the total — check the highlighted lines")
        shot("receipt-review-02-mismatch")
        typeInto(itemTag(1, "price"), "item 1's price", "")
        waitForText("Fix the amounts marked in red")
        assertDisabled(saveButton(), "Save while a price is empty")
        typeInto(itemTag(1, "price"), "item 1's price", "1.30")
        waitForText("Items add up to the total ✓")
        assertEnabled(saveButton(), "Save once the items add up")

        // Rename the milk (so its name is remembered), then categories: everything unset to
        // Groceries, the pasta and tomatoes to Shopping.
        typeInto(itemTag(2, "name"), "item 2's name", "Semi-skimmed milk 2 pints")
        waitForText("On the receipt: SEMI SKIMMED MILK 2PT")
        waitForText("Set category for all unset")
        tapText("Set category for all unset")
        tapMenuItem("Groceries")
        waitUntilGone(isPopup(), "the category menu")
        waitForNoText("Set category for all unset")
        waitForNoText("Category guessed: pick one to confirm it")
        pickCategory(8, "Shopping")
        pickCategory(7, "Shopping")
        shot("receipt-review-03-categorised")

        tap(saveButton(), "Save receipt")
        waitUntilGone(txt("Review receipt"), "the review screen")

        // Activity shows the shop split in two, the hand-entered Tesco row replaced.
        goToTab("Activity")
        waitForExists(row("Tesco · Groceries", gbp(-(ReceiptTestData.totalPence - 85 - 110))), "the Groceries part")
        waitForExists(row("Tesco · Shopping", gbp(-(85 + 110))), "the Shopping part")
        assertNeverShows(row("Tesco", gbp(-ReceiptTestData.totalPence)), "the replaced hand-entered row", forMs = 1_000)
        shot("receipt-review-04-activity-split")

        // Replacing the transaction left the balance exactly as it was.
        goToTab("Home")
        expectAccountRow("Lloyds current", gbp(100_000 - ReceiptTestData.totalPence))

        // The receipt, its items and the learned names are stored.
        val receipts = blocking { appContainer.receiptRepository.observeReceipts().first() }
        assertEquals(1, receipts.size)
        assertEquals(ReceiptTestData.total, receipts.single().total)
        val aliasKeys = blocking { appContainer.receiptRepository.observeAliases().first() }.map { it.rawNameKey }
        assertTrue("the renamed milk is remembered: $aliasKeys", ProductNames.normalise("SEMI SKIMMED MILK 2PT") in aliasKeys)
        val splits = blocking { appContainer.transactionRepository.observeAll().first() }.filter { it.receiptId != null }
        assertEquals(2, splits.size)
        assertTrue(splits.all { it.accountId == account })
    }

    @Test
    fun changingTheDateAndTheReplaceSwitchControlWhatSavingDoes() {
        seedShop()
        openSharedTescoReceipt()
        waitForText(replacesText)

        // A date too far from the hand-entered shop: nothing to replace.
        val earlier = receiptDate.minusDays(5)
        pickDate("Date", shown = receiptDate, target = earlier)
        waitForText("A new transaction will be added")
        waitForNoText(replacesText)
        shot("receipt-review-05-no-match")

        pickDate("Date", shown = earlier, target = receiptDate)
        waitForText(replacesText)
        waitForSwitch(replacesText, on = true)

        // Switch it off: the hand-entered shop stays and the receipt adds its own, so the balance falls twice.
        tapSwitchNextTo(replacesText)
        waitForSwitch(replacesText, on = false)
        waitForText("It stays, and new transactions are added as well, so your balance will change.")
        shot("receipt-review-06-replace-off")
        tap(saveButton(), "Save receipt")
        waitUntilGone(txt("Review receipt"), "the review screen")

        goToTab("Home")
        expectAccountRow("Lloyds current", gbp(100_000 - 2 * ReceiptTestData.totalPence))
    }

    @Test
    fun aReceiptWithNoReadableItemsCanBeTypedIn() {
        seedOnboardingDone()
        seedAccount("Lloyds current", AccountType.CURRENT, 50_000)
        val date = receiptDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        launchMain(sharedText("CORNER SHOP LTD\n$date\nTOTAL   3.50"))
        waitForExists(txt("Review receipt"), "the review screen", HEAVY_TIMEOUT_MS)

        waitForText("Add at least one item")
        assertFieldValue("Store", "Shop")
        assertFieldValue("Total", "3.50")
        assertDisabled(saveButton(), "Save with no items")
        shot("receipt-review-07-no-items")

        tap(hasTestTag(ReceiptReviewTags.ADD_ITEM), "Add item")
        typeInto(itemTag(1, "name"), "the new item's name", "Sandwich")
        typeInto(itemTag(1, "price"), "the new item's price", "3.50")
        waitForText("Items add up to the total ✓")
        waitForText("A new transaction will be added")
        assertEnabled(saveButton(), "Save with one item")
        tap(saveButton(), "Save receipt")
        waitUntilGone(txt("Review receipt"), "the review screen")

        goToTab("Activity")
        waitForExists(row("Shop", gbp(-350)), "the new Shop row")
        shot("receipt-review-08-activity")
    }

    @Test
    fun activityAndQuickAddOfferScanning() {
        seedOnboardingDone()
        seedAccount("Lloyds current", AccountType.CURRENT, 50_000)

        launchQuickAdd()
        waitForText("Scan receipt")
        shot("receipt-review-09-quickadd-chip")
        pressSystemBack()

        launchHome()
        goToTab("Activity")
        tapIcon("Scan receipt")
        waitForText("Take a photo")
        waitForText("Choose from gallery")
        shot("receipt-review-10-scan-screen")
        tapBack()
        waitForExists(txt("Activity"), "the Activity screen again")
    }
}
