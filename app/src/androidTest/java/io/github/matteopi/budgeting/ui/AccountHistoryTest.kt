package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.HistoryLedger
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asFieldText
import io.github.matteopi.budgeting.testing.asHeaderText
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.expectDetailBalance
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.historyRow
import io.github.matteopi.budgeting.testing.monthHeader
import io.github.matteopi.budgeting.testing.openAccountHistory
import io.github.matteopi.budgeting.testing.runningBalance
import io.github.matteopi.budgeting.testing.seedAccount
import io.github.matteopi.budgeting.testing.seedHistoryLedger
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedTransaction
import io.github.matteopi.budgeting.ui.accounts.AccountHistoryTags
import io.github.matteopi.budgeting.ui.accounts.HistoryRange
import io.github.matteopi.budgeting.ui.activity.DirectionFilter
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An account's full history (docs/PLAN.md §6.5): opened from the account page, month headers with their totals,
 * the running balance on every row, filters, the range summary, and editing a row from the list. The ledger is
 * [seedHistoryLedger]: fixed 2025 dates, so months can be named.
 */
@RunWith(AndroidJUnit4::class)
class AccountHistoryTest : UiTest() {

    private val april = monthHeader("April 2025", inPence = 0, outPence = 6_280, endPence = 19_150)
    private val march = monthHeader("March 2025", inPence = 50_000, outPence = 34_570, endPence = 25_430)
    private val february = monthHeader("February 2025", inPence = 0, outPence = 0, endPence = 10_000)

    private fun row(title: String, amount: String) = historyRow(title, amount)

    private fun balance(pence: Long) = runningBalance(pence)

    private fun openHistory(name: String, balancePence: Long) = openAccountHistory(name, balancePence)

    // ---- opening it -------------------------------------------------------------------------------------

    @Test
    fun theAccountPageOpensItsHistoryFromTheButtonAndFromTheBalanceCard() {
        seedHistoryLedger()
        launchHome()
        expectAccountRow(HistoryLedger.LLOYDS, gbp(19_150))
        tap(hasText(HistoryLedger.LLOYDS) and hasText(gbp(19_150)) and hasClickAction(), "the Lloyds account row")
        waitForExists(hasTestTag(ACCOUNT_BALANCE_TAG), "the account page")
        waitForText("Recent activity")
        shot("account-history-01-account-page")

        tapText("See full history")
        waitForText("${HistoryLedger.LLOYDS} history")
        waitForText("Balance over time")
        waitForExists(hasTestTag(AccountHistoryTags.CHART), "the balance chart")
        // 3M is the range it opens on.
        waitForExists(hasTestTag(AccountHistoryTags.range(HistoryRange.MONTHS_3)) and isSelected(), "the 3M chip selected")
        shot("account-history-02-opened")

        // The balance card on the account page is a way in too.
        tapBack()
        waitForExists(hasTestTag(ACCOUNT_BALANCE_TAG), "the account page again")
        expectDetailBalance(gbp(19_150))
        tap(hasTestTag(ACCOUNT_BALANCE_TAG), "the balance card")
        waitForText("${HistoryLedger.LLOYDS} history")
    }

    // ---- months, days and running balances --------------------------------------------------------------

    @Test
    fun monthHeadersShowTheirTotalsAndEveryRowShowsTheBalanceAfterIt() {
        seedHistoryLedger()
        launchHome()
        openHistory(HistoryLedger.LLOYDS, 19_150)

        waitForText(april)
        waitForText(march)
        waitForText(february)
        shot("account-history-03-months")

        // Newest first inside a month, with the running balance after each row.
        waitForExists(row("Top up", "-£20.00") and hasText("Transfer to ${HistoryLedger.SAVINGS}") and balance(19_150), "the Top up row")
        waitForExists(row("Bus pass", "-£15.00") and hasText("Transport") and balance(21_150), "the Bus pass row")
        waitForExists(row("Tesco", "-£27.80") and balance(22_650), "April's Tesco row")
        waitForExists(row("Coffee", "-£3.20") and hasText("Eating out") and balance(25_430), "the Coffee row")
        waitForExists(row("Tesco", "-£42.50") and balance(25_750), "March's Tesco row")
        waitForExists(row("Rent", "-£300.00") and balance(30_000), "the Rent row")
        waitForExists(row("Cafe shifts", "£500.00") and hasText("Wages") and balance(60_000), "the Cafe shifts row")
        waitForExists(row("Opening balance", "£100.00") and balance(10_000), "the opening balance row")
        // Day headers are Activity's.
        waitForText(HistoryLedger.RENT_DAY.asHeaderText())
        waitForText(HistoryLedger.TOP_UP_DAY.asHeaderText())
        shot("account-history-04-rows")
    }

    @Test
    fun theSavingsHistoryShowsTheTransferFromTheOtherSide() {
        seedHistoryLedger()
        launchHome()
        openHistory(HistoryLedger.SAVINGS, 7_000)

        waitForText(monthHeader("April 2025", inPence = 2_000, outPence = 0, endPence = 7_000))
        waitForExists(
            row("Top up", "£20.00") and hasText("Transfer from ${HistoryLedger.LLOYDS}") and balance(7_000),
            "the incoming Top up row",
        )
        waitForExists(row("Opening balance", "£50.00") and balance(5_000), "Savings' opening balance row")
        shot("account-history-05-savings")
    }

    // ---- filters ------------------------------------------------------------------------------------------

    @Test
    fun filtersHideRowsButNeverChangeBalancesOrMonthTotals() {
        seedHistoryLedger()
        launchHome()
        openHistory(HistoryLedger.LLOYDS, 19_150)
        waitForText(march)

        // Category: only Groceries. Both Tesco rows keep the balance they had, March keeps its totals.
        choose("Filter by category", "Groceries")
        waitForExists(row("Tesco", "-£42.50") and balance(25_750), "March's Tesco row")
        waitForExists(row("Tesco", "-£27.80") and balance(22_650), "April's Tesco row")
        waitUntilGone(row("Rent", "-£300.00"), "the Rent row")
        waitUntilGone(row("Cafe shifts", "£500.00"), "the Cafe shifts row")
        waitForText("Showing 2 of 8 transactions", substring = true)
        waitForText(march) // the hidden wages row still counts in the month's "in"
        waitForNoText(february) // a month with nothing to show is left out
        shot("account-history-06-category-filter")

        choose("Filter by category", "All categories")
        waitForExists(row("Rent", "-£300.00"), "the Rent row again")

        // Search: label, category name and note.
        type("Search", "coffee")
        waitForExists(row("Coffee", "-£3.20") and balance(25_430), "the Coffee row")
        waitUntilGone(row("Tesco", "-£42.50"), "March's Tesco row")
        shot("account-history-07-search")
        type("Search", "monthly") // the Bus pass note
        waitForExists(row("Bus pass", "-£15.00") and balance(21_150), "the Bus pass row")
        waitUntilGone(row("Coffee", "-£3.20"), "the Coffee row")
        type("Search", "oat") // the Coffee note
        waitForExists(row("Coffee", "-£3.20"), "the Coffee row by its note")
        type("Search", "transport") // a category name
        waitForExists(row("Bus pass", "-£15.00"), "the Bus pass row by its category")
        type("Search", "zzz")
        waitForText("Nothing matches these filters.")
        shot("account-history-08-nothing-matches")
        tapText("Clear filters")
        waitForExists(row("Rent", "-£300.00"), "the Rent row once the filters are cleared")
        waitForNoText("Showing", substring = true)
    }

    @Test
    fun theInOutAndAllChipsKeepMoneyInOrOut() {
        seedHistoryLedger()
        launchHome()
        openHistory(HistoryLedger.LLOYDS, 19_150)
        waitForText(march)

        tap(hasTestTag(AccountHistoryTags.direction(DirectionFilter.IN)), "the In chip")
        waitForExists(row("Cafe shifts", "£500.00") and balance(60_000), "the income row")
        waitForExists(row("Opening balance", "£100.00"), "the opening balance, which is money in")
        waitUntilGone(row("Rent", "-£300.00"), "the Rent row")
        shot("account-history-09-in-only")

        tap(hasTestTag(AccountHistoryTags.direction(DirectionFilter.OUT)), "the Out chip")
        waitForExists(row("Rent", "-£300.00") and balance(30_000), "the Rent row")
        waitForExists(row("Top up", "-£20.00"), "the transfer out")
        waitUntilGone(row("Cafe shifts", "£500.00"), "the income row")

        tap(hasTestTag(AccountHistoryTags.direction(DirectionFilter.ALL)), "the All chip")
        waitForExists(row("Cafe shifts", "£500.00"), "the income row again")
        waitForExists(row("Rent", "-£300.00"), "the Rent row again")
    }

    // ---- ranges -----------------------------------------------------------------------------------------

    @Test
    fun theRangeChipsChangeTheSummaryOfBalancesAndMoney() {
        seedOnboardingDone()
        val today = LocalDate.now()
        val cash = seedAccount("Cash wallet", AccountType.CASH, 20_000, openedOn = today.minusDays(200))
        seedTransaction(cash, -4_000, today.minusDays(150), "Other", "Photocopying")
        seedTransaction(cash, 500, today.minusDays(100), "Refunds", "Refund")
        seedTransaction(cash, -2_000, today.minusDays(50), "Other", "Printing")
        seedTransaction(cash, -1_000, today.minusDays(10), "Other", "Stamps")
        launchHome()
        openHistory("Cash wallet", 13_500)

        fun expectSummary(start: Long, end: Long, moneyIn: Long, moneyOut: Long) {
            waitFor("the summary to read $start -> $end, in $moneyIn, out $moneyOut") {
                firstText(hasTestTag(AccountHistoryTags.SUMMARY_START)) == gbp(start) &&
                    firstText(hasTestTag(AccountHistoryTags.SUMMARY_END)) == gbp(end) &&
                    firstText(hasTestTag(AccountHistoryTags.SUMMARY_IN)) == gbp(moneyIn) &&
                    firstText(hasTestTag(AccountHistoryTags.SUMMARY_OUT)) == gbp(moneyOut)
            }
        }

        // 3M (the default): the 50-day-old and 10-day-old rows are inside, the 100-day-old refund is not.
        expectSummary(start = 16_500, end = 13_500, moneyIn = 0, moneyOut = 3_000)
        shot("account-history-10-range-3m")

        tap(hasTestTag(AccountHistoryTags.range(HistoryRange.MONTH_1)), "the 1M chip")
        expectSummary(start = 14_500, end = 13_500, moneyIn = 0, moneyOut = 1_000)
        shot("account-history-11-range-1m")

        tap(hasTestTag(AccountHistoryTags.range(HistoryRange.MONTHS_6)), "the 6M chip")
        expectSummary(start = 20_000, end = 13_500, moneyIn = 500, moneyOut = 7_000)

        // 1Y and All reach the opening balance, which counts as where the account started.
        tap(hasTestTag(AccountHistoryTags.range(HistoryRange.YEAR_1)), "the 1Y chip")
        expectSummary(start = 20_000, end = 13_500, moneyIn = 500, moneyOut = 7_000)
        tap(hasTestTag(AccountHistoryTags.range(HistoryRange.ALL)), "the All range chip")
        expectSummary(start = 20_000, end = 13_500, moneyIn = 500, moneyOut = 7_000)
        waitForExists(hasTestTag(AccountHistoryTags.range(HistoryRange.ALL)) and isSelected(), "the All chip selected")
        waitForExists(hasTestTag(AccountHistoryTags.CHART), "the chart")
        shot("account-history-12-range-all")
    }

    // ---- editing from the list ----------------------------------------------------------------------------

    @Test
    fun editingAnAmountFromTheListChangesTheRunningBalancesAndTotals() {
        seedHistoryLedger()
        launchHome()
        openHistory(HistoryLedger.LLOYDS, 19_150)
        waitForText(march)

        tap(row("Tesco", "-£42.50"), "March's Tesco row")
        waitForText("Edit transaction")
        assertFieldValue("Amount", "42.50")
        assertFieldValue("Label", "Tesco")
        assertFieldValue("Category", "Groceries")
        shot("account-history-13-edit-sheet")
        type("Amount", "50")
        tap(clickableText("Save"), "the sheet's Save button")
        waitUntilGone(txt("Edit transaction"), "the edit sheet")

        // 7.50 more went out in March: the totals and every balance after the row follow.
        waitForText(monthHeader("March 2025", inPence = 50_000, outPence = 35_320, endPence = 24_680))
        waitForExists(row("Tesco", "-£50.00") and balance(25_000), "the edited row")
        waitForExists(row("Coffee", "-£3.20") and balance(24_680), "the Coffee row")
        waitForExists(row("Tesco", "-£27.80") and balance(21_900), "April's Tesco row")
        waitForExists(row("Top up", "-£20.00") and balance(18_400), "the Top up row")
        waitForText(monthHeader("April 2025", inPence = 0, outPence = 6_280, endPence = 18_400))
        // Rows before the edit are untouched.
        waitForExists(row("Rent", "-£300.00") and balance(30_000), "the Rent row")
        shot("account-history-14-after-edit")

        // The account page and Home follow.
        tapBack()
        expectDetailBalance(gbp(18_400))
        tapBack()
        expectAccountRow(HistoryLedger.LLOYDS, gbp(18_400))
    }

    @Test
    fun anEarlierDateMovesARowIntoAnEarlierMonth() {
        seedHistoryLedger()
        launchHome()
        openHistory(HistoryLedger.LLOYDS, 19_150)
        waitForText(march)

        tap(row("Coffee", "-£3.20"), "the Coffee row")
        waitForText("Edit transaction")
        assertFieldValue("Date", HistoryLedger.COFFEE_DAY.asFieldText())
        // Dates in the past are all choosable: back to February, a month before the row's own.
        val newDay = LocalDate.of(2025, 2, 24)
        pickDate("Date", shown = HistoryLedger.COFFEE_DAY, target = newDay)
        tap(clickableText("Save"), "the sheet's Save button")
        waitUntilGone(txt("Edit transaction"), "the edit sheet")

        waitForText(monthHeader("February 2025", inPence = 0, outPence = 320, endPence = 9_680))
        waitForText(monthHeader("March 2025", inPence = 50_000, outPence = 34_250, endPence = 25_430))
        waitForExists(row("Coffee", "-£3.20") and balance(9_680), "the Coffee row in February")
        waitForText(newDay.asHeaderText())
        // March's rows now start from the lower balance, and still end where they did.
        waitForExists(row("Cafe shifts", "£500.00") and balance(59_680), "the Cafe shifts row")
        waitForExists(row("Tesco", "-£42.50") and balance(25_430), "March's Tesco row")
        shot("account-history-15-moved-to-february")
    }

    @Test
    fun theStartingBalanceCanBeEditedAndMovedEarlier() {
        val ledger = seedHistoryLedger()
        launchHome()
        openHistory(HistoryLedger.LLOYDS, 19_150)
        waitForText(february)

        tap(row("Opening balance", "£100.00"), "the opening balance row")
        waitForText("Edit starting balance")
        assertFieldValue("Starting balance", "100.00")
        assertFieldValue("Date", HistoryLedger.OPENED.asFieldText())
        shot("account-history-16-edit-starting-balance")
        type("Starting balance", "150")
        val earlier = LocalDate.of(2025, 2, 10)
        pickDate("Date", shown = HistoryLedger.OPENED, target = earlier)
        tap(clickableText("Save"), "the sheet's Save button")
        waitUntilGone(txt("Edit starting balance"), "the edit sheet")

        // 50 more at the start, so every balance after it is 50 higher.
        waitForText(monthHeader("February 2025", inPence = 0, outPence = 0, endPence = 15_000))
        waitForExists(row("Opening balance", "£150.00") and balance(15_000), "the opening balance row")
        waitForExists(row("Cafe shifts", "£500.00") and balance(65_000), "the Cafe shifts row")
        waitForText(monthHeader("April 2025", inPence = 0, outPence = 6_280, endPence = 24_150))
        val opening = blocking {
            appContainer.transactionRepository.observeAll().first()
                .single { it.accountId == ledger.lloydsId && it.kind == TxKind.OPENING_BALANCE }
        }
        assertEquals(earlier, opening.date)
        assertEquals(15_000L, opening.amount.pence)
    }
}
