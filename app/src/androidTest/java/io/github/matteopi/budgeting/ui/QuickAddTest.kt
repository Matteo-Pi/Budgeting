package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.isFinished
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedPreset
import io.github.matteopi.budgeting.testing.seedStandardBudget
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The quick-add card (the widget's "+" opens QuickAddActivity): keypad, categories, presets, date chip, account. */
@RunWith(AndroidJUnit4::class)
class QuickAddTest : UiTest() {

    private fun saveEnabled() = hasText("Save") and hasClickAction() and isEnabled()

    private fun saveDisabled() = hasText("Save") and isNotEnabled()

    private fun selectedChip(text: String) = hasText(text) and isSelected()

    private fun transactionsNow() = blocking { appContainer.transactionRepository.observeAll().first() }

    @Test
    fun typeAnAmountPickACategoryAndSaveLogsTheExpense() {
        val budget = seedStandardBudget()
        val quickAdd = launchQuickAdd()

        waitForText("£0")
        waitForExists(saveDisabled(), "a disabled Save button before anything is entered")
        shot("quickadd-01-empty")

        // The keypad builds the amount; backspace works; a third decimal is ignored.
        typeOnKeypad("12.5")
        waitForText("£12.5")
        tapText("⌫")
        waitForText("£12.")
        typeOnKeypad("50")
        waitForText("£12.50")
        typeOnKeypad("9")
        waitForText("£12.50")
        waitForExists(saveDisabled(), "Save still disabled without a category")
        shot("quickadd-02-amount-typed")

        // Categories are ranked by recent use, so Groceries (most used) is offered; choosing it enables Save.
        tapText("Groceries")
        waitForExists(selectedChip("Groceries"), "the selected Groceries chip")
        waitForExists(saveEnabled(), "an enabled Save button")
        waitForText("Lloyds current") // the default account chip
        tapText("Add label")
        type("Label", "Weekly shop")
        shot("quickadd-03-ready-to-save")

        tap(saveEnabled(), "Save")
        waitFor("QuickAdd to close after saving") { quickAdd.isFinished() }

        // The new transaction is on Home and in Activity, and the money dropped by exactly 12.50.
        launchHome()
        expectYourMoney(gbp(budget.totalPence - 1_250))
        waitForText("Weekly shop")
        shot("quickadd-04-home-after")
        goToTab("Activity")
        waitForText("Today")
        waitForExists(hasText("Weekly shop") and hasText("-£12.50"), "the Weekly shop row in Activity")
        shot("quickadd-05-activity-after")

        val logged = transactionsNow().first { it.label == "Weekly shop" }
        assertEquals(-1_250L, logged.amount.pence)
        assertEquals(budget.lloydsId, logged.accountId)
        assertEquals(LocalDate.now(), logged.date)
    }

    @Test
    fun presetsFillTheFieldsAndInPresetsSwitchToIncome() {
        val budget = seedStandardBudget()
        seedPreset("Meal deal", 385, CategoryFlow.OUT, "Eating out")
        seedPreset("Cafe top-up", 12_000, CategoryFlow.IN, "Wages", sortOrder = 1)
        val quickAdd = launchQuickAdd()

        waitForText("Meal deal £3.85")
        waitForText("Cafe top-up £120.00")
        shot("quickadd-06-presets-offered")

        tapText("Meal deal £3.85")
        waitForText("£3.85")
        assertFieldValue("Label", "Meal deal")
        waitForExists(selectedChip("Eating out"), "the Eating out chip selected by the preset")
        waitForExists(selectedChip("Out"), "the Out toggle")
        waitForExists(saveEnabled(), "Save enabled after a preset fills everything")
        shot("quickadd-07-out-preset-applied")

        // An income preset flips the toggle, replaces the amount and category.
        tapText("Cafe top-up £120.00")
        waitForText("£120")
        waitForExists(selectedChip("In"), "the In toggle")
        waitForExists(selectedChip("Wages"), "the Wages chip selected by the preset")
        assertFieldValue("Label", "Cafe top-up")
        shot("quickadd-08-in-preset-applied")

        tap(saveEnabled(), "Save")
        waitFor("QuickAdd to close after saving") { quickAdd.isFinished() }
        val logged = transactionsNow().first { it.label == "Cafe top-up" }
        assertEquals(12_000L, logged.amount.pence)

        launchHome()
        expectYourMoney(gbp(budget.totalPence + 12_000))
    }

    @Test
    fun outAndInToggleSwapTheCategories() {
        val budget = seedStandardBudget()
        val quickAdd = launchQuickAdd()

        waitForExists(selectedChip("Out"), "Out selected by default")
        waitForExists(hasText("Groceries") and hasClickAction(), "spending category chips")
        assertNoClickable("Wages")
        shot("quickadd-09-out-categories")

        tapText("In")
        waitForExists(selectedChip("In"), "In selected")
        waitForExists(hasText("Wages") and hasClickAction(), "income category chips")
        assertNoClickable("Groceries")
        shot("quickadd-10-in-categories")

        typeOnKeypad("120")
        tapText("Wages")
        waitForExists(selectedChip("Wages"), "Wages chosen")
        waitForExists(saveEnabled(), "Save enabled")

        // Switching direction drops a category that belongs to the other direction.
        tapText("Out")
        waitForExists(saveDisabled(), "Save disabled again after switching direction")
        tapText("In")
        tapText("Wages")
        waitForExists(saveEnabled(), "Save enabled again")
        tap(saveEnabled(), "Save")
        waitFor("QuickAdd to close after saving") { quickAdd.isFinished() }

        launchHome()
        expectYourMoney(gbp(budget.totalPence + 12_000))
    }

    @Test
    fun theDateChipStartsOnTodayAndCanBeSetToYesterday() {
        val budget = seedStandardBudget()
        val today = LocalDate.now()
        val quickAdd = launchQuickAdd()

        waitForExists(hasText("Today") and hasClickAction(), "the date chip reading Today")
        tapText("Today")
        chooseInDatePicker(shown = today, target = today.minusDays(1))
        waitForExists(hasText("Yesterday") and hasClickAction(), "the date chip reading Yesterday")
        shot("quickadd-11-date-yesterday")

        typeOnKeypad("5")
        tapText("Transport")
        tapText("Add label")
        type("Label", "Taxi")
        tap(saveEnabled(), "Save")
        waitFor("QuickAdd to close after saving") { quickAdd.isFinished() }

        val logged = transactionsNow().first { it.label == "Taxi" }
        assertEquals(today.minusDays(1), logged.date)
        assertEquals(-500L, logged.amount.pence)

        launchHome()
        expectYourMoney(gbp(budget.totalPence - 500))
        goToTab("Activity")
        waitForText("Yesterday")
        waitForExists(hasText("Taxi") and hasText("-£5.00"), "the Taxi row")
        shot("quickadd-12-activity-yesterday")
    }

    @Test
    fun theAccountChipDefaultsToTheDefaultAccountAndCanBeChanged() {
        val budget = seedStandardBudget()
        blocking { appContainer.settingsStore.setDefaultAccountId(budget.santanderId) }
        val quickAdd = launchQuickAdd()

        waitForExists(hasText("Santander current") and hasClickAction(), "the account chip on the default account")
        tapText("Santander current")
        waitForExists(menuItem("Santander Savings"), "the account menu")
        shot("quickadd-13-account-menu")
        tapMenuItem("Santander Savings")
        waitForExists(hasText("Santander Savings") and hasClickAction(), "the account chip reading Santander Savings")

        typeOnKeypad("7")
        tapText("Groceries")
        tap(saveEnabled(), "Save")
        waitFor("QuickAdd to close after saving") { quickAdd.isFinished() }

        val logged = transactionsNow().first { it.amount.pence == -700L }
        assertEquals(budget.savingsId, logged.accountId)

        launchHome()
        expectYourMoney(gbp(budget.totalPence - 700))
        expectAccountRow("Santander Savings", gbp(budget.savingsPence - 700))
    }

    @Test
    fun tappingTheDimmedAreaClosesTheCard() {
        seedStandardBudget()
        val quickAdd = launchQuickAdd()
        waitForText("£0")
        compose.onRoot().performTouchInput { click(topCenter.copy(y = 80f)) }
        waitFor("QuickAdd to close when the dimmed area is tapped") { quickAdd.isFinished() }
    }

    @Test
    fun withoutAccountsTheCardAsksToOpenTheApp() {
        seedOnboardingDone()
        val quickAdd = launchQuickAdd()
        waitForText("Add an account first", substring = true, timeoutMs = HEAVY_TIMEOUT_MS)
        shot("quickadd-14-no-accounts")
        tapText("Open app")
        waitFor("QuickAdd to close") { quickAdd.isFinished() }
        waitForExists(hasTestTag(YOUR_MONEY_TAG), "the app's Home", HEAVY_TIMEOUT_MS)
        waitForText("Add your first account")
        shot("quickadd-15-app-opened")
    }

    private fun assertNoClickable(text: String) {
        assertEquals("'$text' must not be offered here.\n${screenDump()}", false, exists(hasText(text) and hasClickAction()))
    }
}
