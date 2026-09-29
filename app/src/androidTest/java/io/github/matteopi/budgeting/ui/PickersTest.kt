package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.asFieldText
import io.github.matteopi.budgeting.testing.asHeaderText
import io.github.matteopi.budgeting.testing.seedAccount
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedSmallLedger
import io.github.matteopi.budgeting.testing.seedStandardBudget
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared pickers: date fields (a read-only text field with a transparent tap target on top;
 * regression: they once ignored taps), the optional date field, and the account and category
 * drop-downs - on plain screens, inside dialogs and inside a bottom sheet.
 */
@RunWith(AndroidJUnit4::class)
class PickersTest : UiTest() {

    private val pastStartNote = "Payments before today are treated as already paid."

    /** A day in the same month as [today] that is not [today] itself (so the field visibly changes). */
    private fun otherDayThisMonth(today: LocalDate): LocalDate =
        today.withDayOfMonth(if (today.dayOfMonth == 15) 16 else 15)

    /** Saves the transaction edit sheet (a bottom sheet, not a Dialog window) and waits for it to close. */
    private fun saveEditSheet() {
        tap(clickableText("Save"), "the edit sheet's Save button")
        waitUntilGone(txt("Edit transaction"), "the edit sheet")
    }

    @Test
    fun dateFieldInADialogOpensThePickerAndChoosingADayUpdatesIt() {
        val today = LocalDate.now()
        seedOnboardingDone()
        launchHome()
        goToTab("Plan")
        tapText("Term dates")
        waitForText("No term dates yet", substring = true)
        tapIcon("Add term")
        waitForText("Add term")
        assertFieldValue("Start date", today.asFieldText())
        shot("pickers-01-term-dialog")

        // Tapping the field opens the Material 3 date picker on top of the dialog.
        tapField("Start date")
        waitForExists(datePickerButton("OK"), "the date picker")
        shot("pickers-02-date-picker-open")
        val target = otherDayThisMonth(today)
        tap(dayInDatePicker(target.dayOfMonth), "day ${target.dayOfMonth}")
        shot("pickers-03-date-picker-day-chosen")
        tap(datePickerButton("OK"), "OK")
        waitUntilGone(datePickerButton("OK"), "the date picker")
        assertFieldValue("Start date", target.asFieldText())
        assertFieldValue("End date", today.asFieldText())
        shot("pickers-04-term-dialog-start-changed")

        // Cancel keeps the old value.
        tapField("End date")
        waitForExists(datePickerButton("Cancel"), "the date picker")
        val other = otherDayThisMonth(today).let { if (it == target) it.plusDays(1) else it }
        tap(dayInDatePicker(other.dayOfMonth), "day ${other.dayOfMonth}")
        tap(datePickerButton("Cancel"), "Cancel")
        waitUntilGone(datePickerButton("OK"), "the date picker")
        assertFieldValue("End date", today.asFieldText())

        // A far-away date needs month navigation; the field shows it in full.
        val later = today.plusMonths(3).withDayOfMonth(10)
        pickDate("End date", shown = today, target = later)
        shot("pickers-05-end-date-three-months-later")
    }

    @Test
    fun dateFieldOnAScreenAndThePastStartNote() {
        val today = LocalDate.now()
        seedStandardBudget()
        launchHome()
        goToTab("Plan")
        tapText("Regular costs")
        tapIcon("Add recurring rule")
        waitForText("Add recurring")
        assertFieldValue("Start date", today.asFieldText())
        assertTrue("No past-start note for a start date of today", !textExists(pastStartNote))

        val past = today.minusDays(3)
        pickDate("Start date", shown = today, target = past)
        waitForText(pastStartNote)
        shot("pickers-06-recurring-past-start")

        val future = today.plusDays(4)
        pickDate("Start date", shown = past, target = future)
        waitForNoText(pastStartNote)
        shot("pickers-07-recurring-future-start")
    }

    @Test
    fun datePickerDisablesFutureDaysWhenTheFieldHasAMaximum() {
        val ledger = seedSmallLedger()
        val today = ledger.today
        launchHome()
        goToTab("Activity")
        tapText("Tesco")
        waitForText("Edit transaction")
        assertFieldValue("Date", today.minusDays(1).asFieldText())

        tapField("Date")
        waitForExists(datePickerButton("OK"), "the date picker")
        val tomorrow = today.plusDays(1)
        if (tomorrow.month == today.month) {
            waitForExists(dayInDatePicker(tomorrow.dayOfMonth), "tomorrow's cell")
            compose.onAllNodes(dayInDatePicker(tomorrow.dayOfMonth))[0].assertIsNotEnabled()
        }
        shot("pickers-08-date-picker-future-disabled")

        // A past day works, and the row moves to that day's group.
        val target = today.minusDays(4)
        chooseInDatePicker(shown = today.minusDays(1), target = target)
        assertFieldValue("Date", target.asFieldText())
        saveEditSheet()
        waitForText(target.asHeaderText())
        shot("pickers-09-transaction-moved")
    }

    @Test
    fun optionalDateFieldShowsPickADateUntilOneIsChosen() {
        val today = LocalDate.now()
        seedOnboardingDone()
        seedAccount("Lloyds current", AccountType.CURRENT, 100_000)
        launchHome()
        goToTab("Plan")
        tapText("Student loan helper")
        waitForText("Instalment 3")
        // No terms exist, so no instalment has a date to start from.
        waitFor("three blank instalment dates") { count(txt("Pick a date")) == 3 }
        shot("pickers-10-optional-date-blank")

        // Cancelling leaves the field blank.
        tapField("Date", index = 0)
        waitForExists(datePickerButton("Cancel"), "the date picker")
        tap(datePickerButton("Cancel"), "Cancel")
        waitUntilGone(datePickerButton("OK"), "the date picker")
        assertEquals(3, count(txt("Pick a date")))

        // Choosing a day fills in just that instalment.
        pickDate("Date", shown = today, target = today.plusDays(20), index = 1)
        assertEquals(2, count(txt("Pick a date")))
        shot("pickers-11-optional-date-chosen")
    }

    @Test
    fun accountAndCategoryDropDownsOpenAndTheSelectionSticks() {
        seedStandardBudget()
        launchHome()
        goToTab("Plan")
        tapText("Regular costs")
        tapIcon("Add recurring rule")
        waitForText("Add recurring")

        // The account picker starts on the default account, opens, and keeps what was chosen.
        assertFieldValue("Account", "Lloyds current")
        tapField("Account")
        waitForExists(menuItem("Santander Savings"), "the account menu")
        shot("pickers-12-account-menu-open")
        assertTrue(exists(menuItem("Lloyds current")) && exists(menuItem("Santander current")))
        tapMenuItem("Santander Savings")
        waitUntilGone(isPopup(), "the menu")
        assertFieldValue("Account", "Santander Savings")
        choose("Account", "Santander current")
        assertFieldValue("Account", "Santander current")

        // The category picker starts empty, lists spending categories, and keeps the choice.
        assertFieldValue("Category", "")
        tapField("Category")
        waitForExists(menuItem("Groceries"), "the category menu")
        assertTrue("the Money-out picker offers spending categories only", !exists(menuItem("Wages")))
        shot("pickers-13-category-menu-open")
        tapMenuItem("Rent & bills")
        waitUntilGone(isPopup(), "the menu")
        assertFieldValue("Category", "Rent & bills")

        // Switching to Money in swaps the list to income categories and clears the choice.
        tapText("Money in")
        assertFieldValue("Category", "")
        tapField("Category")
        waitForExists(menuItem("Wages"), "the income category menu")
        assertTrue("the Money-in picker offers income categories only", !exists(menuItem("Groceries")))
        tapMenuItem("Student loan")
        waitUntilGone(isPopup(), "the menu")
        assertFieldValue("Category", "Student loan")

        // Transfer shows a second account picker that cannot pick the source account.
        tapText("Transfer")
        waitFor("the To account picker") { exists(field("To account")) }
        tapField("To account")
        waitForExists(menuItem("Santander Savings"), "the destination menu")
        assertTrue("the source account is not offered as the destination", !exists(menuItem("Santander current")))
        tapMenuItem("Santander Savings")
        waitUntilGone(isPopup(), "the menu")
        assertFieldValue("To account", "Santander Savings")
        shot("pickers-14-transfer-pickers")
    }

    @Test
    fun pickersWorkInsideABottomSheet() {
        seedSmallLedger()
        launchHome()
        goToTab("Activity")
        tapText("Tesco")
        waitForText("Edit transaction")
        assertFieldValue("Category", "Groceries")
        assertFieldValue("Account", "Lloyds current")

        choose("Category", "Eating out")
        assertFieldValue("Category", "Eating out")
        choose("Account", "Santander current")
        assertFieldValue("Account", "Santander current")
        shot("pickers-15-bottom-sheet-pickers")
        saveEditSheet()

        // The row now belongs to the other account, and the sheet remembers the new category.
        val tescoRow = hasText("Tesco") and hasText("Santander current") and hasClickAction()
        waitForExists(tescoRow, "the Tesco row under Santander current")
        tap(tescoRow, "the Tesco row")
        waitForText("Edit transaction")
        assertFieldValue("Category", "Eating out")
        assertFieldValue("Account", "Santander current")
        shot("pickers-16-bottom-sheet-after-save")
    }
}
