package io.github.matteopi.budgeting.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asFieldText
import io.github.matteopi.budgeting.testing.asShortText
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.hasTextMatching
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedSinkingFund
import io.github.matteopi.budgeting.testing.seedSmallLedger
import io.github.matteopi.budgeting.testing.seedStandardBudget
import io.github.matteopi.budgeting.testing.seedTerm
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Plan tab and its editors: recurring rules, one-offs, terms, student loan, sinking funds, estimates. */
@RunWith(AndroidJUnit4::class)
class PlanTest : UiTest() {

    private val pastStartNote = "Payments up to and including today are treated as already paid."

    private fun saveButton() = hasText("Save") and hasClickAction()

    private fun hubRow(title: String, count: String) = hasText(title) and hasText(count) and hasClickAction()

    @Test
    fun planHubShowsCountsAndTodaysRegime() {
        seedStandardBudget()
        launchHome()
        goToTab("Plan")
        waitForText("Today: term (Autumn)", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForExists(hubRow("Regular costs", "1"), "Regular costs with 1 rule", HEAVY_TIMEOUT_MS)
        waitForExists(hubRow("Money in", "2"), "Money in with 2 loan instalments")
        waitForExists(hubRow("One-off dates", "2"), "One-off dates with 2 items")
        waitForExists(hubRow("Term dates", "2"), "Term dates with 2 terms")
        waitForExists(hubRow("Sinking funds", "0"), "Sinking funds with none")
        waitForExists(hasText("Student loan helper") and hasClickAction(), "the Student loan helper row")
        waitForExists(hasText("Spending estimates") and hasClickAction(), "the Spending estimates row")
        shot("plan-01-hub")
    }

    @Test
    fun recurringRentWithAPastStartIsTreatedAsAlreadyPaidAndNothingIsPosted() {
        val ledger = seedSmallLedger()
        val today = ledger.today
        launchHome()
        goToTab("Plan")
        tapText("Regular costs")
        waitForText("No recurring money yet", substring = true)
        shot("plan-02-regular-money-empty")
        tapIcon("Add recurring rule")
        waitForText("Add recurring")

        type("Label", "Rent")
        type("Amount (£)", "480")
        choose("Category", "Rent & bills")
        val start = today.minusDays(10)
        pickDate("Start date", shown = today, target = start)
        waitForText(pastStartNote)
        // The preview counts from the next payment after today, not the ones that were already paid.
        waitForText("Next: ${start.plusMonths(1).asShortText()}", substring = true)
        shot("plan-03-recurring-past-start")
        tap(saveButton(), "Save")

        waitForExists(hasText("Rent") and hasText("-£480.00") and hasText("Next: ${start.plusMonths(1).asShortText()}"), "the Rent row")
        shot("plan-04-recurring-list")

        // The stored rule counts as posted through today; even the start-of-app posting run adds nothing.
        val rule = blocking { appContainer.planRepository.observeRecurringRules().first().first { it.label == "Rent" } }
        assertEquals("postedThrough", today, rule.postedThrough)
        blocking { appContainer.postingService.postDue(LocalDate.now()) }
        val posted = blocking { appContainer.transactionRepository.observeAll().first().filter { it.label == "Rent" } }
        assertTrue("No Rent transaction may appear for the past; found $posted", posted.isEmpty())

        goToTab("Activity")
        waitForText("Coffee")
        assertNeverShows(txt("Rent"), "a Rent transaction in Activity")
        goToTab("Home")
        expectYourMoney(gbp(ledger.lloydsPence + ledger.santanderPence))
        shot("plan-05-home-unchanged")
    }

    @Test
    fun oneOffScheduledItemCanBeAddedEditedAndDeleted() {
        val ledger = seedSmallLedger()
        val today = ledger.today
        launchHome()
        goToTab("Plan")
        tapText("One-off dates")
        waitForText("No one-off dates yet", substring = true)
        tapIcon("Add one-off date")
        waitForText("Add one-off")

        type("Label", "Deposit")
        type("Amount (£)", "250")
        choose("Category", "Shopping")
        val due = today.plusDays(14)
        pickDate("Date", shown = today, target = due)
        shot("plan-06-add-one-off")
        tap(saveButton(), "Save")

        waitForText("Upcoming (1)")
        waitForExists(hasText("Deposit") and hasText("-£250.00") and hasText("Shopping - Lloyds current - ${due.asShortText()}"), "the Deposit row")
        shot("plan-07-one-off-list")

        // Edit the amount.
        tap(hasText("Deposit") and hasClickAction(), "the Deposit row")
        waitForText("Edit one-off")
        assertFieldValue("Amount (£)", "250.00")
        type("Amount (£)", "300")
        tap(saveButton(), "Save")
        waitForExists(hasText("Deposit") and hasText("-£300.00"), "the edited Deposit row")

        // Delete it (after confirming).
        tap(hasText("Deposit") and hasClickAction(), "the Deposit row")
        waitForText("Edit one-off")
        tapIcon("Delete")
        waitForText("Delete this one-off date?")
        shot("plan-08-delete-one-off")
        tapInDialog("Delete")
        waitForText("No one-off dates yet", substring = true)
    }

    @Test
    fun termDatesWarnAboutOverlapsAndBadRanges() {
        val today = LocalDate.now()
        seedOnboardingDone()
        seedTerm("Autumn", today.minusDays(10), today.plusDays(40))
        launchHome()
        goToTab("Plan")
        tapText("Term dates")
        waitForText("Today: term (Autumn)")
        waitForText("Autumn (current)")
        shot("plan-09-term-dates")

        tapIcon("Add term")
        waitForText("Add term")
        // A new term defaults to today..today, inside Autumn: warned, but the name is still required.
        waitForText("Overlaps with Autumn.")
        waitForExists(hasText("Save") and isNotEnabled(), "Save disabled without a name")
        type("Name (e.g. Autumn)", "Clash")
        waitForExists(hasText("Save") and isEnabled(), "Save enabled once named (an overlap is only a warning)")
        shot("plan-10-term-overlap-warning")

        // End before start is an error and blocks saving.
        pickDate("End date", shown = today, target = today.minusDays(3))
        waitForText("The end date can't be before the start date.")
        waitForExists(hasText("Save") and isNotEnabled(), "Save disabled for an end before the start")
        shot("plan-11-term-end-before-start")

        // A clear range saves.
        pickDate("Start date", shown = today, target = today.plusDays(100))
        pickDate("End date", shown = today.minusDays(3), target = today.plusDays(130))
        waitForNoText("Overlaps with", substring = true)
        waitForNoText("The end date can't be before the start date.")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the term dialog")
        waitForText("Clash")
        shot("plan-12-terms-two")
    }

    @Test
    fun studentLoanHelperRequiresDatesThenCreatesThreeInstalments() {
        val today = LocalDate.now()
        val ledger = seedSmallLedger()
        launchHome()
        goToTab("Plan")
        tapText("Student loan helper")
        waitForText("Instalment 3")
        waitFor("three blank dates") { count(txt("Pick a date")) == 3 }
        shot("plan-13-student-loan-blank")

        // Split a year's total in three; the last instalment takes the odd penny.
        tap(isToggleable(), "the split switch")
        type("Year's total (£)", "4600")
        // The keyboard is up after typing, so not every instalment fits on screen at once: look for each
        // amount (scrolling as needed). The exact three amounts are checked in the database below.
        waitForExists(txt("£1,533.33"), "the first instalments' amount")
        waitForExists(txt("£1,533.34"), "the last instalment's amount, with the odd penny")
        shot("plan-14-student-loan-split")

        // Without dates nothing can be created.
        tapText("Create the three instalments")
        waitForText("Pick a date for every instalment.")
        shot("plan-15-student-loan-needs-dates")
        val before = blocking { appContainer.planRepository.observeScheduledItems().first() }
        assertTrue("Nothing may be created without dates", before.isEmpty())

        // Each instalment's date field reads "Pick a date" until chosen, so pick the next blank one each
        // time (the list only lays out the fields on screen, so counting fields by index would not work).
        for (target in listOf(today.plusDays(30), today.plusDays(100), today.plusDays(170))) {
            tap(field("Date") and hasText("Pick a date"), "the next blank instalment date")
            chooseInDatePicker(shown = today, target = target)
        }
        waitForNoText("Pick a date")
        shot("plan-16-student-loan-dated")
        tapText("Create the three instalments")

        // Back on the Plan hub: three incomes were added, to be confirmed when they arrive.
        waitForExists(hubRow("Money in", "3"), "Money in with the 3 new instalments", HEAVY_TIMEOUT_MS)
        val items = blocking { appContainer.planRepository.observeScheduledItems().first() }
        assertEquals(3, items.size)
        assertEquals(listOf(153_333L, 153_333L, 153_334L), items.sortedBy { it.date }.map { it.amount.pence })
        assertEquals(listOf(today.plusDays(30), today.plusDays(100), today.plusDays(170)), items.map { it.date }.sorted())
        assertTrue(items.all { it.isMajorIncome && it.postingMode == PostingMode.CONFIRM && it.label == "Student loan" })
        assertEquals(ledger.lloydsId, items.first().accountId)
        shot("plan-17-plan-after-student-loan")
    }

    @Test
    fun sinkingFundIsValidatedSavedCompletedAndCancelled() {
        val today = LocalDate.now()
        seedSmallLedger()
        seedSinkingFund("Laptop", 90_000, today, today.plusMonths(4))
        launchHome()
        goToTab("Plan")
        tapText("Sinking funds")
        waitForText("Laptop", timeoutMs = HEAVY_TIMEOUT_MS)
        tapIcon("Add sinking fund")
        waitForText("Add fund")
        assertFieldValue("Start date", today.asFieldText())
        assertFieldValue("Target date", today.plusMonths(3).asFieldText())
        assertFieldValue("Category (optional)", "None")

        tap(saveButton(), "Save")
        waitForText("Enter a name.")
        type("Name", "Interrail")
        tap(saveButton(), "Save")
        waitForText("Enter a target amount greater than zero.")
        type("Target (£)", "600")
        // A target date that is not after the start date is refused.
        pickDate("Target date", shown = today.plusMonths(3), target = today)
        tap(saveButton(), "Save")
        waitForText("The target date must be after the start date.")
        shot("plan-18-sinking-fund-errors")

        val target = today.plusMonths(2)
        pickDate("Target date", shown = today, target = target)
        choose("Category (optional)", "Other")
        assertFieldValue("Category (optional)", "Other")
        shot("plan-19-sinking-fund-form")
        tap(saveButton(), "Save")

        waitForExists(hasText("Interrail") and hasText("Target £600.00 by ${target.asShortText()}"), "the Interrail fund")
        waitForText("Reserved today")
        shot("plan-20-sinking-funds")

        // Mark it as done: it moves to the "Done and cancelled" section.
        tap(hasText("Interrail") and hasClickAction(), "the Interrail fund")
        waitForText("Edit fund")
        assertFieldValue("Name", "Interrail")
        assertFieldValue("Target (£)", "600.00")
        tapText("Mark as done")
        waitForText("Done and cancelled")
        waitForExists(hasText("Interrail") and hasText("Done"), "the completed fund")

        // Cancel the other one.
        tap(hasText("Laptop") and hasClickAction(), "the Laptop fund")
        waitForText("Edit fund")
        tapText("Cancel fund")
        waitForExists(hasText("Laptop") and hasText("Cancelled"), "the cancelled fund")
        shot("plan-21-sinking-funds-done-and-cancelled")
    }

    /** The texts of the first "Term" row on the estimates screen (Groceries in term time). */
    private fun groceriesTermRow(): List<String> = runCatching {
        compose.onAllNodes(hasText("Term") and hasClickAction()).fetchSemanticsNodes().first()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }.getOrDefault(emptyList())

    private fun waitForGroceriesTermRow(what: String, condition: (List<String>) -> Boolean) =
        waitFor("the Groceries term row $what (it reads ${groceriesTermRow()})", HEAVY_TIMEOUT_MS) {
            condition(groceriesTermRow())
        }

    @Test
    fun spendingEstimatesCanBeEditedInEveryMode() {
        val today = LocalDate.now()
        seedSmallLedger()
        // History is all holidays and the first term is still ahead, so term-time estimates are missing.
        seedTerm("Spring", today.plusDays(30), today.plusDays(100))
        launchHome()
        val needed = hasTextMatching(Regex("[0-9]+ spending estimates? needed"))
        waitForExists(needed, "Home's 'spending estimates needed' card", HEAVY_TIMEOUT_MS)
        shot("plan-22-home-needs-estimates")
        tap(needed and hasClickAction(), "the 'spending estimates needed' card")

        waitForText("Groceries", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForGroceriesTermRow("to ask for input") { "Needs input" in it }
        shot("plan-23-estimates")

        // The first row is Groceries in term time.
        tapText("Term", index = 0)
        waitForText("Groceries - term")
        waitForText("Automatic estimate:", substring = true)
        shot("plan-24-estimate-dialog")

        // "My estimate": with no term data yet it is used as it is.
        tapInDialog("My estimate")
        type("Weekly amount (£)", "60")
        tapInDialog("Save")
        waitUntilGone(txt("Groceries - term"), "the estimate dialog")
        waitForGroceriesTermRow("to show my 60.00 estimate") { texts ->
            "Needs input" !in texts && texts.any { it.startsWith("Your estimate") } && texts.any { it.contains("£60.00") }
        }
        shot("plan-25-estimate-my-estimate")

        // Reopening shows what was saved; switch to "Always use my number".
        tapText("Term", index = 0)
        waitForText("Groceries - term")
        assertFieldValue("Weekly amount (£)", "60.00")
        tapInDialog("Always use my number")
        type("Weekly amount (£)", "70")
        tapInDialog("Save")
        waitUntilGone(txt("Groceries - term"), "the estimate dialog")
        waitForGroceriesTermRow("to show 70.00") { texts -> texts.any { it.contains("£70.00") } }

        // "Adjust by %" scales the automatic estimate.
        tapText("Term", index = 0)
        waitForText("Groceries - term")
        tapInDialog("Adjust by %")
        type("Adjust by % (e.g. -20)", "-20")
        shot("plan-26-estimate-adjust")
        tapInDialog("Save")
        waitUntilGone(txt("Groceries - term"), "the estimate dialog")
        waitForGroceriesTermRow("to be adjusted (no longer 70.00)") { texts ->
            texts.none { it.contains("£70.00") } && texts.any { it.startsWith("Your estimate") }
        }
        tapText("Term", index = 0)
        waitForText("Groceries - term")
        assertFieldValue("Adjust by % (e.g. -20)", "-20")

        // Back to automatic: the row asks for input again.
        tapInDialog("Automatic")
        waitForText("Uses the automatic estimate above", substring = true)
        tapInDialog("Save")
        waitUntilGone(txt("Groceries - term"), "the estimate dialog")
        waitForGroceriesTermRow("to need input again") { texts ->
            "Needs input" in texts && texts.none { it.startsWith("Your estimate") }
        }

        // "I don't spend on this" makes it a zero estimate.
        tapText("Term", index = 0)
        waitForText("Groceries - term")
        tapInDialog("I don't spend on this")
        waitUntilGone(txt("Groceries - term"), "the estimate dialog")
        waitForGroceriesTermRow("to become a zero estimate") { texts ->
            "Needs input" !in texts && texts.any { it.startsWith("Your estimate") }
        }
        shot("plan-27-estimate-zero")

        // The filter keeps only estimates that still need input: Groceries' term estimate (now zero)
        // drops out, while its holiday one, never set, stays. Categories are listed Groceries first,
        // so the first term row left on screen must belong to a later category.
        tap(isToggleable(), "the 'Needs input only' switch")
        waitForText("Eating out")
        waitFor("Groceries' term row to drop out") {
            topOf(hasText("Term") and hasClickAction()) > topOf(txt("Eating out"))
        }
        waitForExists(hasText("Holiday") and hasText("Needs input") and hasClickAction(), "a holiday estimate still needing input")
        shot("plan-28-estimates-needs-input-only")
    }
}
