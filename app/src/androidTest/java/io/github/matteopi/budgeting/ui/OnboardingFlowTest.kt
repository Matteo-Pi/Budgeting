package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asFieldText
import io.github.matteopi.budgeting.testing.blocking
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The user's real first-run scenario, entirely through the UI: three accounts, terms, student loan,
 * rent, a couple of weekly estimates - then Home must show the true total, a positive
 * safe-to-spend and no "short" message (regression for "Your money GBP 0" plus "short").
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest : UiTest() {

    private val accountsLabelBalance = "Current balance (as shown in your bank app)"

    @Test
    fun fullOnboardingShowsTheRightTotalAndAPositiveSafeToSpend() {
        val today = LocalDate.now()
        launchMain()

        // 1. Welcome
        waitForText("Welcome to Budgeting", timeoutMs = HEAVY_TIMEOUT_MS)
        shot("onboarding-01-welcome")
        tapText("Next")

        // 2. Accounts
        waitForText("Your accounts")
        waitForText("No accounts yet", substring = true)
        shot("onboarding-02-accounts-empty")

        // Lloyds current: typed by hand, with a GBP 1,500 overdraft.
        tapText("Add account")
        type("Name", "Lloyds current")
        type(accountsLabelBalance, "6579.30")
        type("Overdraft limit", "1500")
        shot("onboarding-03-add-account-lloyds")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the add-account dialog")
        waitFor("the Lloyds current card") { count(txt("Current account")) >= 1 }

        // Santander Savings: with the account type chosen from the drop-down.
        tapText("Add account")
        type("Name", "Santander Savings")
        choose("Type", "Savings account")
        assertFieldValue("Type", "Savings account")
        type(accountsLabelBalance, "400")
        type("Overdraft limit", "0")
        shot("onboarding-04-add-account-savings")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the add-account dialog")
        waitFor("the savings card") { count(txt("Savings account")) >= 1 }

        // Santander current: from the suggestion chip.
        tapText("Santander current")
        assertFieldValue("Name", "Santander current")
        type(accountsLabelBalance, "500")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the add-account dialog")
        waitFor("all three account cards") { count(txt("Current account")) >= 2 && count(txt("Savings account")) >= 1 }
        shot("onboarding-05-accounts-added")
        tapText("Next")

        // 3. Money display: keep the defaults (GBP 0 = empty account, buffer 100).
        waitForText("How should we show your balance?")
        assertFieldValue("Safety buffer (£)", "100")
        shot("onboarding-06-money-display")
        tapText("Next")

        // 4. Term dates
        waitForText("Your term dates")
        waitForText("No term dates yet.")
        tapText("Add term")
        type("Name (e.g. Autumn)", "Autumn")
        pickDate("Start date", shown = today, target = today.minusDays(7))
        pickDate("End date", shown = today, target = today.plusDays(70))
        shot("onboarding-07-add-term-autumn")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the add-term dialog")
        waitForText("${today.minusDays(7)} - ${today.plusDays(70)}")

        tapText("Add term")
        type("Name (e.g. Autumn)", "Spring")
        pickDate("Start date", shown = today, target = today.plusDays(100))
        pickDate("End date", shown = today, target = today.plusDays(170))
        shot("onboarding-08-add-term-spring")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the add-term dialog")
        waitForText("${today.plusDays(100)} - ${today.plusDays(170)}")
        shot("onboarding-09-terms-added")
        tapText("Next")

        // 5. Money in: the student loan instalments (the first date defaults to the next term start).
        waitForText("Money coming in")
        assertFieldValue("Date", today.plusDays(100).asFieldText(), index = 0)
        assertFieldValue("Date", "Pick a date", index = 1)
        assertFieldValue("Date", "Pick a date", index = 2)
        shot("onboarding-10-money-in-before")
        pickDate("Date", shown = today, target = today.plusDays(200), index = 1)
        pickDate("Date", shown = today, target = today.plusDays(300), index = 2)
        type("Amount (£)", "1600", index = 0)
        type("Amount (£)", "1600", index = 1)
        type("Amount (£)", "1600", index = 2)
        shot("onboarding-11-money-in-filled")
        tapText("Save the three instalments")
        waitForText("Instalments saved.")
        shot("onboarding-12-money-in-saved")
        tapText("Next")

        // 6. Regular costs: rent GBP 550 monthly, from the suggestion chip, with the default start date (today).
        waitForText("Rent, phone, subscriptions", substring = true)
        tapText("Rent")
        assertFieldValue("Label", "Rent")
        type("Amount (£)", "550")
        assertFieldValue("Category", "Rent & bills")
        shot("onboarding-13-regular-costs-form")
        tapText("Add cost")
        waitForText("-£550.00")
        shot("onboarding-14-regular-costs-added")
        tapText("Next")

        // 7. Weekly spending estimates: a couple of numbers and one "I don't spend on this".
        waitForText("Weekly spending estimates")
        type("In term", "45", index = 0)
        type("In the holidays", "40", index = 0)
        type("In term", "20", index = 1)
        type("In the holidays", "25", index = 1)
        tapText("I don't spend on this", index = 2)
        assertFieldValue("In term", "45", index = 0)
        assertFieldValue("In term", "0", index = 2)
        shot("onboarding-15-estimates")
        tapText("Next")

        // 8. Statement import (skipped) and 9. widget (skipped)
        waitForText("Past spending (optional)")
        shot("onboarding-16-statement-import-step")
        tapText("Skip")
        waitForText("Add the widget")
        shot("onboarding-17-widget-step")
        tapText("Skip")

        // 10. Done
        waitForText("You're all set")
        shot("onboarding-18-done")
        tapText("Start using Budgeting")

        // Home: the true total, a positive safe-to-spend, and no shortfall message.
        waitForExists(hasTestTag(YOUR_MONEY_TAG), "Home's 'Your money' figure", HEAVY_TIMEOUT_MS)
        expectYourMoney("£7,479.30")
        waitFor("a positive safe-to-spend per week on Home", HEAVY_TIMEOUT_MS) { (safeToSpendPerWeek() ?: 0L) > 0L }
        assertTrue(
            "Home must not claim a shortfall.\n${screenDump()}",
            !textExists("Even with no spending", substring = true),
        )
        expectAccountRow("Lloyds current", "£6,579.30")
        expectAccountRow("Santander Savings", "£400.00")
        expectAccountRow("Santander current", "£500.00")
        // Recent activity is below the fold: scroll Home's list down to it first.
        waitForExists(txt("Opening balance"), "Recent activity")
        waitFor("the three opening balances in Recent activity") { count(txt("Opening balance")) >= 3 }
        shot("onboarding-19-home")

        // The rent starts today, so nothing may be posted retroactively - not even on the next app start.
        blocking { appContainer.postingService.postDue(LocalDate.now()) }
        val rentPosted = blocking { appContainer.transactionRepository.observeAll().first().any { it.label == "Rent" } }
        assertEquals("Rent must not be posted for dates before it was set up", false, rentPosted)
        expectYourMoney("£7,479.30")

        // Settings > About agrees with what Home shows (both come from the same forecast snapshot).
        openSettings()
        waitForText("Forecast updated", substring = true, timeoutMs = HEAVY_TIMEOUT_MS)
        scrollIntoView(txt("Forecast updated", substring = true))
        val diagnostics = firstText(txt("Forecast updated", substring = true)).orEmpty()
        assertTrue("About says: $diagnostics", diagnostics.contains("3 accounts"))
        assertTrue("About says: $diagnostics", diagnostics.contains("total £7,479.30"))
        shot("onboarding-20-settings-about")
    }

    @Test
    fun everyStepCanBeSkippedAndBackWorks() {
        launchMain()
        waitForText("Welcome to Budgeting", timeoutMs = HEAVY_TIMEOUT_MS)
        shot("onboarding-skip-01-welcome")

        val steps = listOf(
            "Your accounts" to "accounts",
            "How should we show your balance?" to "money-display",
            "Your term dates" to "term-dates",
            "Money coming in" to "money-in",
            "Regular costs" to "regular-costs",
            "Weekly spending estimates" to "estimates",
            "Past spending (optional)" to "statement-import",
            "Add the widget" to "widget",
            "You're all set" to "done",
        )
        steps.forEachIndexed { index, (heading, slug) ->
            tapText("Skip")
            waitForText(heading)
            shot("onboarding-skip-${(index + 2).toString().padStart(2, '0')}-$slug")
            if (heading == "Your term dates") {
                // Back returns to the previous step, and Next comes forward again.
                tapText("Back")
                waitForText("How should we show your balance?")
                tapText("Next")
                waitForText("Your term dates")
            }
        }

        tapText("Start using Budgeting")
        waitForExists(hasTestTag(YOUR_MONEY_TAG), "Home's 'Your money' figure", HEAVY_TIMEOUT_MS)
        waitForText("Add your first account")
        waitForText("No transactions yet", substring = true)
        expectYourMoney("£0.00")
        shot("onboarding-skip-11-home-empty")
    }
}
