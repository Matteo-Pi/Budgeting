package io.github.matteopi.budgeting.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import io.github.matteopi.budgeting.core.model.AccountType
import java.time.LocalDate

/**
 * A small ledger with fixed dates in 2025 for the account history and editing tests: months are named in the
 * list, so the dates must not move with "today". Every figure below is worked out by hand.
 *
 * Lloyds current (no overdraft), balance after each row:
 *  - 20 Feb: opening balance GBP 100.00 -> 100.00
 *  - 3 Mar: Cafe shifts +500.00 (Wages) -> 600.00
 *  - 5 Mar: Rent -300.00 (Rent & bills) -> 300.00, then Tesco -42.50 (Groceries) -> 257.50
 *  - 12 Mar: Coffee -3.20 (Eating out, note "oat latte") -> 254.30
 *  - 2 Apr: Tesco -27.80 (Groceries) -> 226.50
 *  - 10 Apr: Bus pass -15.00 (Transport, note "monthly") -> 211.50
 *  - 15 Apr: Top up -20.00, a transfer to Santander Savings -> 191.50
 *
 * Month headers: February in 0.00 / out 0.00 / ends 100.00; March in 500.00 / out 345.70 / ends 254.30;
 * April in 0.00 / out 62.80 / ends 191.50. Santander Savings: opening 50.00 on 20 Feb, plus the 20.00 transfer
 * -> 70.00.
 */
class HistoryLedger(val lloydsId: Long, val savingsId: Long) {
    companion object {
        val OPENED: LocalDate = LocalDate.of(2025, 2, 20)
        val WAGES_DAY: LocalDate = LocalDate.of(2025, 3, 3)
        val RENT_DAY: LocalDate = LocalDate.of(2025, 3, 5)
        val COFFEE_DAY: LocalDate = LocalDate.of(2025, 3, 12)
        val APRIL_SHOP_DAY: LocalDate = LocalDate.of(2025, 4, 2)
        val BUS_DAY: LocalDate = LocalDate.of(2025, 4, 10)
        val TOP_UP_DAY: LocalDate = LocalDate.of(2025, 4, 15)

        const val LLOYDS = "Lloyds current"
        const val SAVINGS = "Santander Savings"
    }
}

fun seedHistoryLedger(): HistoryLedger {
    seedOnboardingDone()
    val lloyds = seedAccount(HistoryLedger.LLOYDS, AccountType.CURRENT, 10_000, openedOn = HistoryLedger.OPENED)
    val savings = seedAccount(HistoryLedger.SAVINGS, AccountType.SAVINGS, 5_000, openedOn = HistoryLedger.OPENED)
    seedTransaction(lloyds, 50_000, HistoryLedger.WAGES_DAY, "Wages", "Cafe shifts")
    seedTransaction(lloyds, -30_000, HistoryLedger.RENT_DAY, "Rent & bills", "Rent")
    seedTransaction(lloyds, -4_250, HistoryLedger.RENT_DAY, "Groceries", "Tesco")
    seedTransaction(lloyds, -320, HistoryLedger.COFFEE_DAY, "Eating out", "Coffee", note = "oat latte")
    seedTransaction(lloyds, -2_780, HistoryLedger.APRIL_SHOP_DAY, "Groceries", "Tesco")
    seedTransaction(lloyds, -1_500, HistoryLedger.BUS_DAY, "Transport", "Bus pass", note = "monthly")
    seedTransfer(lloyds, savings, 2_000, HistoryLedger.TOP_UP_DAY, "Top up")
    return HistoryLedger(lloyds, savings)
}

/** The month header the history list shows: "March 2025 · in £500.00 · out £345.70 · ends at £254.30". */
fun monthHeader(month: String, inPence: Long, outPence: Long, endPence: Long): String =
    "$month · in ${gbp(inPence)} · out ${gbp(outPence)} · ends at ${gbp(endPence)}"

/** A list row (Activity, an account's page or its history) showing [title] and [amount]. */
fun historyRow(title: String, amount: String): SemanticsMatcher = hasText(title) and hasText(amount) and hasClickAction()

/** The small "Balance £59.83" text on a history row. */
fun runningBalance(pence: Long): SemanticsMatcher = hasText("Balance ${gbp(pence)}")

/** Waits until the account page's balance headline reads [balance]. */
fun UiTest.expectDetailBalance(balance: String) {
    waitFor("the account page balance to read $balance") { firstText(hasTestTag(UiTest.ACCOUNT_BALANCE_TAG)) == balance }
}

/** Home -> the page of the account called [name], which Home shows with [balancePence]. */
fun UiTest.openAccountPage(name: String, balancePence: Long) {
    expectAccountRow(name, gbp(balancePence))
    tap(hasText(name) and hasText(gbp(balancePence)) and hasClickAction(), "the '$name' account row")
    waitForExists(hasTestTag(UiTest.ACCOUNT_BALANCE_TAG), "the account page")
}

/** Home -> the account's page -> "See full history". */
fun UiTest.openAccountHistory(name: String, balancePence: Long) {
    openAccountPage(name, balancePence)
    tapText("See full history")
    waitForText("$name history")
}
