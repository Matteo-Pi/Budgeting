package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.HistoryLedger
import io.github.matteopi.budgeting.testing.SeedReceiptItem
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.awaitCategories
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.expectDetailBalance
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.historyRow
import io.github.matteopi.budgeting.testing.openAccountHistory
import io.github.matteopi.budgeting.testing.openAccountPage
import io.github.matteopi.budgeting.testing.runningBalance
import io.github.matteopi.budgeting.testing.seedAccount
import io.github.matteopi.budgeting.testing.seedHistoryLedger
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedReceipt
import io.github.matteopi.budgeting.testing.seedSmallLedger
import io.github.matteopi.budgeting.testing.seedTransaction
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Editing past transactions through the one edit sheet (docs/PLAN.md §6.5, "Editing"): from an account's page,
 * its history and Activity. A transaction becomes a move between accounts, a transfer is pointed somewhere else
 * or stops being a transfer, and a category change can be applied to similar transactions.
 */
@RunWith(AndroidJUnit4::class)
class EditPastTransactionsTest : UiTest() {

    private fun row(title: String, amount: String) = historyRow(title, amount)

    private fun balance(pence: Long) = runningBalance(pence)

    private fun balanceOf(accountId: Long): Long = blocking {
        (appContainer.accountRepository.observeBalances().first()[accountId] ?: Money.ZERO).pence
    }

    private fun waitForBalance(accountId: Long, pence: Long) =
        waitFor("account $accountId to hold ${gbp(pence)}") { balanceOf(accountId) == pence }

    private fun transactions() = blocking { appContainer.transactionRepository.observeAll().first() }

    /** The names of the categories of every transaction labelled [label] ("none" for no category). */
    private fun categoriesOf(label: String): List<String> = blocking {
        val names = appContainer.categoryRepository.observeCategories().first().associate { it.id to it.name }
        appContainer.transactionRepository.observeAll().first().filter { it.label == label }.map { names[it.categoryId] ?: "none" }
    }

    private fun merchantRules() = blocking { appContainer.importRepository.observeMerchantRules().first() }

    private fun openSheetFor(title: String, amount: String, sheetTitle: String = "Edit transaction") {
        tap(row(title, amount), "the '$title' row")
        waitForText(sheetTitle)
    }

    private fun saveSheet(sheetTitle: String) {
        tap(clickableText("Save"), "the sheet's Save button")
        waitUntilGone(txt(sheetTitle), "the edit sheet")
    }

    // ---- from the account page ------------------------------------------------------------------------

    @Test
    fun aRecentActivityRowOnTheAccountPageOpensTheEditSheet() {
        seedHistoryLedger()
        launchHome()
        openAccountPage(HistoryLedger.LLOYDS, 19_150)

        openSheetFor("Coffee", "-£3.20")
        assertFieldValue("Amount", "3.20")
        shot("edit-01-sheet-from-the-account-page")
        type("Amount", "4")
        saveSheet("Edit transaction")

        expectDetailBalance(gbp(19_150 - 80))
        waitForExists(row("Coffee", "-£4.00"), "the edited Coffee row")
    }

    // ---- a normal transaction becomes a move between accounts ---------------------------------------------

    @Test
    fun convertingATransactionIntoAMoveShowsItOnTheOtherAccount() {
        seedHistoryLedger()
        launchHome()
        openAccountHistory(HistoryLedger.LLOYDS, 19_150)

        openSheetFor("Bus pass", "-£15.00")
        waitForExists(field("Category"), "the Category field")
        tapSwitchNextTo("Move between my accounts")
        // The category goes; the other account takes its place, "Not in the app" until one is chosen.
        waitForExists(field("Other account"), "the Other account field")
        waitUntilGone(field("Category"), "the Category field")
        assertFieldValue("Other account", "Not in the app")
        shot("edit-02-move-between-accounts")
        choose("Other account", HistoryLedger.SAVINGS)
        assertFieldValue("Other account", HistoryLedger.SAVINGS)
        saveSheet("Edit transaction")

        // Still on Lloyds, now as a transfer: the amount and the balances are as they were.
        waitForExists(
            row("Bus pass", "-£15.00") and hasText("Transfer to ${HistoryLedger.SAVINGS}") and balance(21_150),
            "the Bus pass row as a transfer",
        )
        waitUntilGone(row("Bus pass", "-£15.00") and hasText("Transport"), "the row's old category")
        shot("edit-03-after-the-move")

        // And it is on the other account too.
        tapBack()
        expectDetailBalance(gbp(19_150))
        tapBack()
        openAccountHistory(HistoryLedger.SAVINGS, 8_500)
        waitForExists(
            row("Bus pass", "£15.00") and hasText("Transfer from ${HistoryLedger.LLOYDS}") and balance(6_500),
            "the Bus pass row on Savings",
        )
        waitForExists(row("Top up", "£20.00") and balance(8_500), "the Top up row on Savings")
        shot("edit-04-on-the-other-account")
    }

    @Test
    fun aMoveToAnAccountNotInTheAppLeavesALoneLegThatShowsAsSuch() {
        val ledger = seedHistoryLedger()
        launchHome()
        openAccountHistory(HistoryLedger.LLOYDS, 19_150)

        openSheetFor("Rent", "-£300.00")
        tapSwitchNextTo("Move between my accounts")
        waitForExists(field("Other account"), "the Other account field")
        saveSheet("Edit transaction")

        waitForExists(
            row("Rent", "-£300.00") and hasText("Transfer · other account not in the app") and balance(30_000),
            "the Rent row as a lone transfer leg",
        )
        val legs = transactions().filter { it.kind == TxKind.TRANSFER && it.label == "Rent" }
        assertEquals(1, legs.size)
        assertEquals(ledger.lloydsId, legs.single().accountId)
        assertNull(legs.single().categoryId)
        // Nothing moved to the other account.
        assertEquals(7_000L, balanceOf(ledger.savingsId))
    }

    // ---- editing a transfer -----------------------------------------------------------------------------

    @Test
    fun aTransferCanBePointedAtAnotherAccountOrAtOneNotInTheApp() {
        val ledger = seedHistoryLedger()
        val cash = seedAccount("Cash wallet", AccountType.CASH, 0, openedOn = HistoryLedger.OPENED)
        launchHome()
        openAccountHistory(HistoryLedger.LLOYDS, 19_150)

        // To the cash wallet instead of Savings.
        openSheetFor("Top up", "-£20.00", sheetTitle = "Edit transfer")
        assertFieldValue("Amount", "20.00")
        assertFieldValue("From account", HistoryLedger.LLOYDS)
        assertFieldValue("To account", HistoryLedger.SAVINGS)
        shot("edit-05-transfer-editor")
        choose("To account", "Cash wallet")
        saveSheet("Edit transfer")
        waitForExists(row("Top up", "-£20.00") and hasText("Transfer to Cash wallet") and balance(19_150), "the Top up row to the wallet")
        waitForBalance(cash, 2_000)
        waitForBalance(ledger.savingsId, 5_000)

        // To an account that isn't in the app: the other leg goes.
        openSheetFor("Top up", "-£20.00", sheetTitle = "Edit transfer")
        assertFieldValue("To account", "Cash wallet")
        choose("To account", "Not in the app")
        waitForText("The other side is an account that isn't in the app.", substring = true)
        saveSheet("Edit transfer")
        waitForExists(
            row("Top up", "-£20.00") and hasText("Transfer · other account not in the app") and balance(19_150),
            "the Top up row as a lone leg",
        )
        waitForBalance(cash, 0)
        waitForBalance(ledger.savingsId, 5_000)
        assertEquals(1, transactions().count { it.kind == TxKind.TRANSFER })

        // Back to Savings: the missing leg is created again. The same account on both sides is refused.
        openSheetFor("Top up", "-£20.00", sheetTitle = "Edit transfer")
        assertFieldValue("To account", "Not in the app")
        choose("To account", HistoryLedger.LLOYDS)
        waitForText("Pick two different accounts.")
        waitForExists(hasText("Save") and isNotEnabled(), "Save disabled with the same account on both sides")
        shot("edit-06-same-account-refused")
        choose("To account", HistoryLedger.SAVINGS)
        saveSheet("Edit transfer")
        waitForExists(
            row("Top up", "-£20.00") and hasText("Transfer to ${HistoryLedger.SAVINGS}") and balance(19_150),
            "the Top up row back to Savings",
        )
        waitForBalance(ledger.savingsId, 7_000)
        assertEquals(2, transactions().count { it.kind == TxKind.TRANSFER })
    }

    @Test
    fun notATransferFromAnAccountsHistoryKeepsThatAccount() {
        val ledger = seedHistoryLedger()
        launchHome()
        openAccountHistory(HistoryLedger.LLOYDS, 19_150)

        openSheetFor("Top up", "-£20.00", sheetTitle = "Edit transfer")
        tapText("Not a transfer")
        waitForText("Make it a normal transaction")
        // Opened from Lloyds' history, so Lloyds' side is the one kept and the dialog says what goes.
        waitForText("The other side of this transfer, on ${HistoryLedger.SAVINGS}, will be deleted.")
        assertNeverShows(field("Keep it on"), "the account choice", forMs = 500)
        waitForExists(hasText("Make normal") and isNotEnabled(), "Make normal disabled before a category is picked")
        choose("Category", "Transport")
        shot("edit-07-not-a-transfer")
        tapInDialog("Make normal")
        waitUntilGone(txt("Make it a normal transaction"), "the dialog")
        waitUntilGone(txt("Edit transfer"), "the edit sheet")

        waitForExists(row("Top up", "-£20.00") and hasText("Transport") and balance(19_150), "the Top up row as spending")
        waitUntilGone(hasText("Transfer to ${HistoryLedger.SAVINGS}"), "the transfer badge")
        assertTrue(transactions().none { it.kind == TxKind.TRANSFER })
        waitForBalance(ledger.savingsId, 5_000)
        waitForBalance(ledger.lloydsId, 19_150)
    }

    @Test
    fun notATransferFromActivityAsksWhichAccountToKeepItOn() {
        val ledger = seedSmallLedger()
        launchHome()
        goToTab("Activity")

        openSheetFor("Top up", "£50.00", sheetTitle = "Edit transfer")
        tapText("Not a transfer")
        waitForText("Make it a normal transaction")
        // From Activity the transfer isn't on either account: the user says which side to keep.
        waitForExists(field("Keep it on"), "the account choice")
        waitForExists(hasText("Make normal") and isNotEnabled(), "Make normal disabled before an account is chosen")
        choose("Keep it on", "Santander current")
        waitForText("The other side of this transfer, on Lloyds current, will be deleted.")
        // The money arrived in Santander, so only income categories are offered.
        choose("Category", "Family")
        shot("edit-08-not-a-transfer-from-activity")
        tapInDialog("Make normal")
        waitUntilGone(txt("Edit transfer"), "the edit sheet")

        waitForExists(row("Top up", "£50.00") and hasText("Santander current"), "the Top up row as income on Santander")
        waitForNoText("Lloyds current → Santander current")
        val kept = transactions().single { it.label == "Top up" }
        assertEquals(TxKind.NORMAL, kept.kind)
        assertEquals(ledger.santanderId, kept.accountId)
        assertEquals(listOf("Family"), categoriesOf("Top up"))
        // Lloyds got its 50 back.
        goToTab("Home")
        expectAccountRow("Lloyds current", gbp(ledger.lloydsPence + 5_000))
        expectAccountRow("Santander current", gbp(ledger.santanderPence))
    }

    // ---- change similar ---------------------------------------------------------------------------------

    @Test
    fun changingACategoryOffersToChangeTheSameMerchantEverywhere() {
        seedOnboardingDone()
        val today = LocalDate.now()
        val account = seedAccount("Current", AccountType.CURRENT, 100_000)
        seedTransaction(account, -1_000, today.minusDays(1), "Shopping", "TESCO STORES")
        seedTransaction(account, -2_000, today.minusDays(2), "Shopping", "TESCO STORES")
        seedTransaction(account, -3_000, today.minusDays(3), "Shopping", "TESCO STORES")
        seedTransaction(account, -300, today.minusDays(4), "Shopping", "COSTA COFFEE")
        seedTransaction(account, -400, today.minusDays(5), "Shopping", "COSTA COFFEE")
        seedTransaction(account, -500, today.minusDays(6), "Shopping", "Boots")
        launchHome()
        goToTab("Activity")
        val groceries = awaitCategories().getValue("Groceries")

        // "Change all": the other two Tesco rows follow, and the choice is remembered for imports.
        openSheetFor("TESCO STORES", "-£10.00")
        choose("Category", "Groceries")
        saveSheet("Edit transaction")
        waitForText("Change the other 2 \"TESCO STORES\" transactions to Groceries too?")
        waitForText("Use Groceries for TESCO STORES in future imports")
        shot("edit-09-change-similar")
        tapInDialog("Change all")
        waitFor("every Tesco row to be in Groceries") { categoriesOf("TESCO STORES").all { it == "Groceries" } }
        assertEquals(listOf("Shopping", "Shopping"), categoriesOf("COSTA COFFEE"))
        assertEquals(listOf("Shopping"), categoriesOf("Boots"))
        waitFor("the Tesco rule to be saved") { merchantRules().any { it.merchantKey == "TESCO STORES" } }
        assertEquals(groceries, merchantRules().single { it.merchantKey == "TESCO STORES" }.categoryId)

        // "Just this one", with the rule unticked: nothing else changes and no rule is saved.
        openSheetFor("COSTA COFFEE", "-£3.00")
        choose("Category", "Eating out")
        saveSheet("Edit transaction")
        waitForText("Change the other \"COSTA COFFEE\" transaction to Eating out too?")
        tap(txt("Use Eating out for COSTA COFFEE in future imports"), "the remember-rule checkbox")
        tapInDialog("Just this one")
        waitFor("one Costa row to be in Eating out") { categoriesOf("COSTA COFFEE").sorted() == listOf("Eating out", "Shopping") }
        assertNeverShows(txt("Change similar transactions?"), "the question", forMs = 500)
        assertEquals("no rule for Costa", listOf("TESCO STORES"), merchantRules().map { it.merchantKey })

        // A merchant nobody else uses: no question.
        openSheetFor("Boots", "-£5.00")
        choose("Category", "Health")
        saveSheet("Edit transaction")
        waitFor("Boots to be in Health") { categoriesOf("Boots") == listOf("Health") }
        assertNeverShows(txt("Change similar transactions?"), "the question")
    }

    @Test
    fun theQuestionAlsoFollowsAnEditMadeFromAnAccountsHistory() {
        seedHistoryLedger()
        launchHome()
        openAccountHistory(HistoryLedger.LLOYDS, 19_150)

        openSheetFor("Tesco", "-£42.50")
        choose("Category", "Shopping")
        saveSheet("Edit transaction")
        waitForText("Change the other \"TESCO\" transaction to Shopping too?")
        tapInDialog("Change all")
        waitFor("both Tesco rows to be in Shopping") { categoriesOf("Tesco") == listOf("Shopping", "Shopping") }
        assertTrue(merchantRules().any { it.merchantKey == "TESCO" })
    }

    // ---- receipts ---------------------------------------------------------------------------------------

    @Test
    fun aReceiptPartExplainsItselfAndCannotBecomeATransfer() {
        seedOnboardingDone()
        val account = seedAccount("Current", AccountType.CURRENT, 100_000)
        seedReceipt(
            account, "TESCO", LocalDate.now().minusDays(2),
            listOf(SeedReceiptItem("TS WHITE LOAF", "White bread", 120), SeedReceiptItem("USB CABLE", null, 500, category = "Shopping")),
        )
        launchHome()
        goToTab("Activity")

        openSheetFor("Tesco · Groceries", "-£1.20")
        waitForText("Part of a receipt. Changing the amount makes it differ from the receipt.")
        waitForExists(hasText("View receipt") and hasClickAction(), "the View receipt button")
        waitForNoText("Move between my accounts")
        shot("edit-10-receipt-part")
    }
}
