package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asShortText
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedStandardBudget
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Adding, viewing, transferring between, reconciling, editing, archiving and deleting accounts. */
@RunWith(AndroidJUnit4::class)
class AccountsTest : UiTest() {

    private fun accountRow(name: String, balance: String) = hasText(name) and hasText(balance) and hasClickAction()

    private fun openAccountFromHome(name: String, balance: String) {
        expectAccountRow(name, balance)
        tap(accountRow(name, balance), "the '$name' account row")
        waitForExists(hasTestTag(ACCOUNT_BALANCE_TAG), "the account detail's balance")
    }

    private fun expectDetailBalance(balance: String) {
        waitFor("the account detail balance to read $balance") { firstText(hasTestTag(ACCOUNT_BALANCE_TAG)) == balance }
    }

    @Test
    fun addAccountsFromHome() {
        seedOnboardingDone()
        launchHome()

        // With no accounts Home offers to add the first one.
        waitForText("Add your first account")
        shot("accounts-01-home-no-accounts")
        tapText("Add account")
        waitForExists(field("Current balance"), "the add-account form")
        type("Name", "Cash wallet")
        choose("Type", "Cash")
        type("Current balance", "80")
        shot("accounts-02-add-first-account")
        tap(hasText("Save") and hasClickAction() and isEnabled(), "Save")

        expectYourMoney("£80.00")
        expectAccountRow("Cash wallet", "£80.00")
        shot("accounts-03-home-one-account")

        // A second account from the Accounts card: a credit card that is left out of safe-to-spend.
        tapText("Add account")
        waitForExists(field("Current balance"), "the add-account form")
        type("Name", "Credit card")
        choose("Type", "Credit card")
        type("Current balance", "-120")
        type("Overdraft limit", "500")
        tap(isToggleable(), "the 'Include in safe-to-spend' switch")
        shot("accounts-04-add-credit-card")
        tap(hasText("Save") and hasClickAction() and isEnabled(), "Save")

        expectAccountRow("Credit card", "-£120.00")
        expectAccountRow("Cash wallet", "£80.00")
        // "All accounts" counts the card even though it is left out of safe-to-spend...
        expectYourMoney("-£40.00")
        shot("accounts-05-home-two-accounts")
        // ...and "Safe-to-spend accounts" leaves it out, so that figure is unchanged.
        tap(hasTestTag("account-view-chip") and hasText("All accounts"), "the account chip on the 'Your money' card")
        tapMenuItem("Safe-to-spend accounts")
        expectYourMoney("£80.00")
    }

    @Test
    fun addAccountFormValidatesItsFields() {
        seedOnboardingDone()
        launchHome()
        tapText("Add account")
        waitForExists(field("Current balance"), "the add-account form")
        waitForExists(hasText("Save") and isNotEnabled(), "Save disabled while the name is empty")
        type("Name", "Test")
        waitForExists(hasText("Save") and isEnabled(), "Save enabled once the name is valid")
        type("Current balance", "abc")
        waitForExists(hasText("Save") and isNotEnabled(), "Save disabled for a balance that is not a number")
        shot("accounts-06-invalid-balance")
        type("Current balance", "12.34")
        type("Overdraft limit", "-5")
        waitForExists(hasText("Save") and isNotEnabled(), "Save disabled for a negative overdraft")
        type("Overdraft limit", "0")
        waitForExists(hasText("Save") and isEnabled(), "Save enabled again")
    }

    @Test
    fun accountDetailShowsBalancesAndTransfersMoneyBetweenAccounts() {
        val budget = seedStandardBudget()
        launchHome()
        openAccountFromHome("Lloyds current", "£4,862.15")
        expectDetailBalance("£4,862.15")
        waitForText("Overdraft limit: £1,200.00")
        waitForText("Reconciliation history")
        waitForText("No balance checks yet.")
        shot("accounts-07-detail")

        tapText("Transfer")
        waitForText("Transfer from Lloyds current")
        assertFieldValue("To account", "Santander Savings")
        waitForExists(hasText("Transfer") and isNotEnabled(), "the Transfer button disabled without an amount")
        type("Amount", "250")
        type("Label", "Top up savings")
        shot("accounts-08-transfer-dialog")
        tapInDialog("Transfer")
        waitUntilGone(txt("Transfer from Lloyds current"), "the transfer dialog")

        expectDetailBalance("£4,612.15")
        waitForExists(
            hasText("Top up savings") and hasText("Lloyds current → Santander Savings"),
            "the transfer in the account's recent activity",
        )
        shot("accounts-09-detail-after-transfer")

        // Home: both balances changed, the total is untouched (a transfer is not spending).
        tapBack()
        expectYourMoney(budget.total)
        expectAccountRow("Lloyds current", "£4,612.15")
        expectAccountRow("Santander Savings", "£600.00")
        shot("accounts-10-home-after-transfer")

        // It shows once in Activity.
        goToTab("Activity")
        waitForText("Top up savings")
        assertEquals(1, count(txt("Top up savings")))
    }

    @Test
    fun updateBalancePreviewsTheGapAndRecordsAnUntrackedEntry() {
        val budget = seedStandardBudget()
        launchHome()
        // No balance has been checked yet, so Home nudges.
        waitForText("You haven't checked a balance", substring = true)
        openAccountFromHome("Lloyds current", "£4,862.15")

        tapText("Update balance")
        waitForText("Enter the balance your bank app shows for Lloyds current.")
        assertFieldValue("Balance", "4862.15")
        waitForText("nothing to record", substring = true)
        shot("accounts-11-update-balance-matches")

        type("Balance", "4800")
        waitForText("The app thought £4,862.15. The £62.15 difference will be recorded as Untracked.")
        shot("accounts-12-update-balance-preview")
        tapInDialog("Confirm")
        waitUntilGone(txt("Enter the balance your bank app shows", substring = true), "the update-balance dialog")

        expectDetailBalance("£4,800.00")
        waitForText("-£62.15 adjustment")
        waitForText(budget.today.asShortText() + " " + budget.today.year)
        waitForExists(hasText("Balance update") and hasText("Lloyds current · Reconciliation"), "the reconciliation entry")
        shot("accounts-13-detail-after-update")

        // In Activity the gap is filed under the Untracked category.
        goToTab("Activity")
        tapText("Category")
        tapMenuItem("Untracked")
        waitForExists(hasText("Balance update") and hasText("-£62.15"), "the Balance update row")
        waitForNoText("Tesco")
        tap(hasText("Balance update") and hasClickAction(), "the Balance update row")
        waitForText("Edit transaction")
        assertFieldValue("Category", "Untracked")
        shot("accounts-14-untracked-entry")
        pressSystemBack()
        waitUntilGone(txt("Edit transaction"), "the edit sheet")

        // Home has the new total and the nudge is gone now that a balance was checked.
        goToTab("Home")
        expectYourMoney(gbp(budget.totalPence - 6_215))
        waitForNoText("You haven't checked a balance", substring = true)
        shot("accounts-15-home-after-update")
    }

    @Test
    fun editRenameAndArchiveAnAccount() {
        val budget = seedStandardBudget()
        launchHome()
        openAccountFromHome("Santander Savings", "£350.00")
        tapIcon("Edit account")
        waitForText("Edit account")
        assertFieldValue("Name", "Santander Savings")
        assertFieldValue("Type", "Savings account")
        assertFieldValue("Overdraft limit", "0.00")
        shot("accounts-16-edit-account")

        // Rename: back on the detail screen the title follows.
        type("Name", "Santander Saver")
        tap(hasText("Save") and hasClickAction() and isEnabled(), "Save")
        waitForText("Santander Saver")
        expectDetailBalance("£350.00")

        // Archive: the account leaves Home and the total.
        tapIcon("Edit account")
        waitForText("Edit account")
        tapText("Archive")
        waitUntilGone(txt("Edit account"), "the edit screen")
        goToTab("Home")
        expectYourMoney(gbp(budget.totalPence - budget.savingsPence))
        waitUntilGone(accountRow("Santander Saver", "£350.00"), "the archived account's row")
        expectAccountRow("Lloyds current", "£4,862.15")
        shot("accounts-17-home-after-archive")
    }

    @Test
    fun deleteAnAccountAfterConfirmation() {
        val budget = seedStandardBudget()
        launchHome()
        openAccountFromHome("Santander Savings", "£350.00")
        tapIcon("Edit account")
        waitForText("Edit account")
        tapText("Delete account")
        waitForText("Delete this account?")
        waitForText("This deletes all its transactions and can't be undone.")
        shot("accounts-18-delete-confirmation")

        // Cancel keeps it.
        tapInDialog("Cancel")
        waitForNoText("Delete this account?")
        tapText("Delete account")
        tapInDialog("Delete")
        waitForNoText("Delete this account?")

        goToTab("Home")
        expectYourMoney(gbp(budget.totalPence - budget.savingsPence), HEAVY_TIMEOUT_MS)
        waitUntilGone(accountRow("Santander Savings", "£350.00"), "the deleted account's row")
        val names = blocking { appContainer.accountRepository.observeAccounts().first().map { it.name } }
        assertTrue("Santander Savings must be gone from the database: $names", "Santander Savings" !in names)
        shot("accounts-19-home-after-delete")
    }
}
