package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asHeaderText
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedSmallLedger
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Activity tab: day grouping, the edit sheet, deleting with confirmation, and filters/search. */
@RunWith(AndroidJUnit4::class)
class ActivityTest : UiTest() {

    private fun row(title: String, amount: String) = hasText(title) and hasText(amount) and hasClickAction()

    /** A button of the "Delete transaction?" confirmation dialog (the sheet behind it has its own Delete button). */
    private fun deleteDialogButton(text: String) =
        hasText(text) and hasClickAction() and hasAnyAncestor(hasAnyDescendant(hasText("Delete transaction?")))

    @Test
    fun transactionsAreGroupedByDayWithHeaders() {
        val ledger = seedSmallLedger()
        val today = ledger.today
        launchHome()
        goToTab("Activity")

        waitForText("Today")
        waitForText("Yesterday")
        waitForText(today.minusDays(2).asHeaderText())
        waitForText(today.minusDays(3).asHeaderText())
        waitForText(today.minusDays(5).asHeaderText())
        waitForText(today.minusDays(30).asHeaderText())

        waitForExists(row("Coffee", "-£3.20"), "the Coffee row")
        waitForExists(row("Tesco", "-£24.60"), "the Tesco row")
        waitForExists(row("Bus pass", "-£18.00"), "the Bus pass row")
        waitForExists(row("Cafe shifts", "£100.00"), "the Cafe shifts row")
        // A transfer appears once, as "from -> to", with the amount that moved.
        waitForExists(row("Top up", "£50.00") and hasText("Lloyds current → Santander current"), "the Top up transfer row")
        waitForExists(hasText("Opening balance") and hasText("Lloyds current · Opening balance"), "Lloyds' opening balance row")

        // Newest day first.
        val order = listOf(
            "Today",
            "Yesterday",
            today.minusDays(2).asHeaderText(),
            today.minusDays(3).asHeaderText(),
            today.minusDays(5).asHeaderText(),
        ).map { topOf(txt(it)) }
        assertEquals("day headers must be newest first: $order", order.sorted(), order)
        shot("activity-01-grouped-by-day")
    }

    @Test
    fun editingAnAmountAndDateMovesTheTransactionAndChangesTheBalance() {
        val ledger = seedSmallLedger()
        val today = ledger.today
        launchHome()
        goToTab("Activity")
        tap(row("Tesco", "-£24.60"), "the Tesco row")
        waitForText("Edit transaction")
        assertFieldValue("Amount", "24.60")
        assertFieldValue("Label", "Tesco")
        assertFieldValue("Category", "Groceries")
        assertFieldValue("Account", "Lloyds current")
        shot("activity-02-edit-sheet")

        type("Amount", "30.00")
        pickDate("Date", shown = today.minusDays(1), target = today.minusDays(4))
        shot("activity-03-edit-sheet-changed")
        tap(clickableText("Save"), "the sheet's Save button")
        waitUntilGone(txt("Edit transaction"), "the edit sheet")

        waitForExists(row("Tesco", "-£30.00"), "the edited Tesco row")
        waitForText(today.minusDays(4).asHeaderText())
        // Tesco was the only transaction yesterday, so that group is gone.
        waitForNoText("Yesterday")
        shot("activity-04-after-edit")

        // Lloyds lost another 5.40; total money follows.
        goToTab("Home")
        expectYourMoney(gbp(ledger.lloydsPence + ledger.santanderPence - 540))
        expectAccountRow("Lloyds current", gbp(ledger.lloydsPence - 540))
    }

    @Test
    fun editingATransferChangesBothAccounts() {
        val ledger = seedSmallLedger()
        launchHome()
        goToTab("Activity")
        tap(row("Top up", "£50.00"), "the Top up row")
        waitForText("Edit transfer")
        assertFieldValue("Amount", "50.00")
        shot("activity-05-edit-transfer")
        type("Amount", "80")
        tap(clickableText("Save"), "the sheet's Save button")
        waitUntilGone(txt("Edit transfer"), "the edit sheet")
        waitForExists(row("Top up", "£80.00"), "the edited transfer row")

        goToTab("Home")
        // 30 more left Lloyds and arrived in Santander: the total is unchanged.
        expectYourMoney(gbp(ledger.lloydsPence + ledger.santanderPence))
        expectAccountRow("Lloyds current", gbp(ledger.lloydsPence - 3_000))
        expectAccountRow("Santander current", gbp(ledger.santanderPence + 3_000))
    }

    @Test
    fun deletingATransactionAsksForConfirmation() {
        val ledger = seedSmallLedger()
        launchHome()
        goToTab("Activity")
        tap(row("Bus pass", "-£18.00"), "the Bus pass row")
        waitForText("Edit transaction")
        tapText("Delete")
        waitForText("Delete transaction?")
        waitForText("This can't be undone.")
        shot("activity-06-delete-confirmation")

        // Cancel keeps the transaction.
        tap(deleteDialogButton("Cancel"), "Cancel in the confirmation")
        waitForNoText("Delete transaction?")
        waitForText("Edit transaction")

        // Confirm removes it, and its money comes back to the balance.
        tapText("Delete")
        waitForText("Delete transaction?")
        tap(deleteDialogButton("Delete"), "Delete in the confirmation")
        waitForNoText("Delete transaction?")
        waitUntilGone(txt("Edit transaction"), "the edit sheet")
        waitUntilGone(txt("Bus pass"), "the deleted Bus pass row")
        shot("activity-07-after-delete")

        goToTab("Home")
        expectYourMoney(gbp(ledger.lloydsPence + ledger.santanderPence + 1_800))
        val stillThere = blocking { appContainer.transactionRepository.observeAll().first().any { it.label == "Bus pass" } }
        assertTrue("Bus pass must be gone from the database", !stillThere)
    }

    @Test
    fun searchAndFiltersNarrowTheList() {
        seedSmallLedger()
        launchHome()
        goToTab("Activity")
        waitForText("Coffee")

        // Search by label.
        tapIcon("Search")
        type("Search label", "tes")
        waitForNoText("Coffee")
        waitForExists(row("Tesco", "-£24.60"), "the Tesco row")
        waitForNoText("Today")
        shot("activity-08-search")
        type("Search label", "zzz")
        waitForText("Nothing matches these filters.")
        shot("activity-09-nothing-matches")
        type("Search label", "")
        waitForText("Coffee")
        tapIcon("Close search")

        // Category filter.
        tapText("Category")
        waitForExists(menuItem("Groceries"), "the category filter menu")
        shot("activity-10-category-menu")
        tapMenuItem("Groceries")
        waitForNoText("Coffee")
        waitForExists(row("Tesco", "-£24.60"), "the Tesco row")
        waitForExists(hasText("Groceries") and isSelected(), "the Groceries filter chip")
        // "All" clears it.
        tap(hasText("Groceries") and isSelected(), "the Groceries filter chip")
        tapMenuItem("All")
        waitForText("Coffee")

        // In / Out chips.
        tapText("In")
        waitForNoText("Coffee")
        waitForExists(row("Cafe shifts", "£100.00"), "the income row")
        shot("activity-11-in-only")
        tapText("Out")
        waitForNoText("Cafe shifts")
        waitForNoText("Top up") // transfers are neither in nor out
        waitForExists(row("Coffee", "-£3.20"), "the Coffee row")
        shot("activity-12-out-only")
        tapText("Out") // tapping the selected chip switches the filter off again
        waitForText("Cafe shifts")
        waitForText("Top up")

        // Account filter: Santander current keeps its own rows and the transfer, drops Lloyds-only rows.
        tapText("Account")
        waitForExists(menuItem("Santander current"), "the account filter menu")
        tapMenuItem("Santander current")
        waitForNoText("Coffee")
        waitForNoText("Tesco")
        waitForExists(row("Bus pass", "-£18.00"), "the Bus pass row")
        waitForExists(hasText("Top up"), "the transfer, which touches Santander current")
        shot("activity-13-account-filter")
    }

    @Test
    fun emptyStateAndTheAddButton() {
        seedOnboardingDone()
        launchHome()
        goToTab("Activity")
        waitForText("No transactions yet", substring = true)
        shot("activity-14-empty")
        // The + button opens the quick-add card (which asks for an account first, as there are none).
        tapIcon("Add transaction")
        waitForText("Add an account first", substring = true)
        shot("activity-15-quick-add-from-activity")
        pressSystemBack()
        waitForText("No transactions yet", substring = true)
    }
}
