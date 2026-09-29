package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asHeaderText
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.gbp
import io.github.matteopi.budgeting.testing.hasTextMatching
import io.github.matteopi.budgeting.testing.seedStandardBudget
import io.github.matteopi.budgeting.testing.testApp
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Home's "Your money" view switching, and every part of Settings (appearance, defaults, privacy, data, about). */
@RunWith(AndroidJUnit4::class)
class HomeAndSettingsTest : UiTest() {

    private fun appSettingsNow() = blocking { appContainer.settingsStore.appSettings.first() }

    private fun budgetSettingsNow() = blocking { appContainer.settingsStore.budgetSettings.first() }

    /** The chip beside "Your money" (Accounts rows can have the same text, but they come later on the screen). */
    private fun openYourMoneyMenu(currentLabel: String) {
        tap(hasText(currentLabel) and hasClickAction(), "the account chip on the 'Your money' card", index = 0)
    }

    @Test
    fun homeShowsTheHeroAccountsAndRecentActivity() {
        val budget = seedStandardBudget()
        launchHome()
        expectYourMoney(budget.total)
        waitFor("a positive safe-to-spend per week", HEAVY_TIMEOUT_MS) { (safeToSpendPerWeek() ?: 0L) > 0L }
        waitForText("Safe to spend")
        waitForText("Across all accounts included in safe-to-spend")
        waitForExists(hasTextMatching(Regex("£[0-9,]+ a day.*")), "the per-day line under the hero")
        waitForText("50 days to Student loan")
        expectAccountRow("Lloyds current", "£6,579.30")
        expectAccountRow("Santander Savings", "£400.00")
        expectAccountRow("Santander current", gbp(budget.santanderPence))
        waitForText("Next 8 weeks · All accounts")
        waitForText("Recent activity")
        shot("home-01-standard-budget")

        // "See all" leads to the full list (yesterday's Tesco shop is in the standard history).
        tapText("See all")
        waitForText("Yesterday")
        shot("home-02-see-all-opens-activity")
    }

    @Test
    fun yourMoneySwitchesBetweenAllAccountsAndOneAccount() {
        val budget = seedStandardBudget()
        launchHome()
        expectYourMoney(budget.total)
        waitForExists(hasText("All accounts") and hasClickAction(), "the 'All accounts' chip")
        shot("home-03-your-money-all")

        openYourMoneyMenu("All accounts")
        waitForExists(menuItem("Santander Savings"), "the account menu")
        assertTrue(exists(menuItem("All accounts")) && exists(menuItem("Lloyds current")) && exists(menuItem("Santander current")))
        shot("home-04-your-money-menu")
        tapMenuItem("Santander Savings")
        expectYourMoney("£400.00")
        waitForText("Next 8 weeks · Santander Savings")
        shot("home-05-your-money-savings")

        openYourMoneyMenu("Santander Savings")
        tapMenuItem("Lloyds current")
        expectYourMoney("£6,579.30")
        waitForText("Next 8 weeks · Lloyds current")
        shot("home-06-your-money-lloyds")

        openYourMoneyMenu("Lloyds current")
        tapMenuItem("Santander current")
        expectYourMoney(gbp(budget.santanderPence))
        shot("home-07-your-money-santander")

        // Safe-to-spend stays pool-wide whatever account is shown.
        waitFor("safe-to-spend to still be shown", HEAVY_TIMEOUT_MS) { (safeToSpendPerWeek() ?: 0L) > 0L }

        openYourMoneyMenu("Santander current")
        tapMenuItem("All accounts")
        expectYourMoney(budget.total)
        waitForText("Next 8 weeks · All accounts")
    }

    @Test
    fun settingsDefaultAccountCanBeChangedAndQuickAddFollows() {
        val budget = seedStandardBudget()
        launchHome()
        expectYourMoney(budget.total)
        openSettings()
        scrollIntoView(txt("Default account"))
        assertFieldValue("Account", "Lloyds current")
        shot("settings-01-default-account")
        choose("Account", "Santander current")
        assertFieldValue("Account", "Santander current")
        waitFor("the default account to be saved") { budgetSettingsNow().defaultAccountId == budget.santanderId }

        // Quick add (Home's + button) now starts on that account.
        tapBack()
        expectYourMoney(budget.total)
        tapIcon("Add transaction")
        waitForExists(hasText("Santander current") and hasClickAction(), "the quick-add account chip")
        shot("settings-02-quick-add-default-account")
        pressSystemBack()
        waitForExists(hasTestTag(YOUR_MONEY_TAG), "Home after closing quick add")
    }

    @Test
    fun everyThemeInLightAndDarkLooksRightOnHome() {
        val budget = seedStandardBudget()
        launchHome()
        expectYourMoney(budget.total)
        for (mode in listOf(DarkMode.LIGHT, DarkMode.DARK)) {
            var first = true
            for (theme in AppTheme.entries) {
                openSettings()
                tapText(mode.label)
                tap(hasText(theme.label) and hasClickAction(), "the ${theme.label} theme card")
                waitForExists(hasText(theme.label) and isSelected(), "the ${theme.label} card to be selected")
                waitFor("${theme.name} / ${mode.name} to be saved") {
                    val saved = appSettingsNow()
                    saved.theme == theme && saved.darkMode == mode
                }
                if (first) {
                    shot("theme-settings-${mode.name.lowercase()}")
                    first = false
                }
                tapBack()
                expectYourMoney(budget.total)
                shot("theme-${theme.name.lowercase()}-${mode.name.lowercase()}-home")
            }
        }
    }

    @Test
    fun privacyTogglesRemindersAndForecastOptionsDoNotCrash() {
        val today = LocalDate.now()
        // The reminder switch asks for the notification permission on Android 13+; grant it so no system dialog appears.
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(testApp.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        seedStandardBudget()
        launchHome()
        openSettings()

        // Privacy: hide widget amounts.
        waitForText("Hide widget amounts")
        scrollIntoView(txt("Hide widget amounts"))
        shot("settings-03-privacy-before")
        tapSwitchNextTo("Hide widget amounts")
        waitForSwitch("Hide widget amounts", true)
        waitFor("hide-widget-amounts to be saved") { appSettingsNow().hideWidgetAmounts }
        tapSwitchNextTo("Hide widget amounts")
        waitForSwitch("Hide widget amounts", false)

        // Hide in recent apps (the window becomes secure; screenshots are blank while it is on).
        tapSwitchNextTo("Hide in recent apps")
        waitForSwitch("Hide in recent apps", true)
        waitFor("secure-screen to be saved") { appSettingsNow().secureScreen }
        tapSwitchNextTo("Hide in recent apps")
        waitForSwitch("Hide in recent apps", false)
        waitFor("secure-screen to be cleared") { !appSettingsNow().secureScreen }

        // App lock: refused with an explanation when the device has no screen lock, otherwise it locks.
        tapSwitchNextTo("App lock")
        waitFor("app lock to be refused or to lock the app") {
            textExists("Set up a fingerprint", substring = true) || textExists("Budgeting is locked")
        }
        if (textExists("Budgeting is locked")) {
            blocking { appContainer.settingsStore.setAppLockEnabled(false) }
            waitForNoText("Budgeting is locked")
        } else {
            shot("settings-04-app-lock-refused")
        }

        // Reminders: the day chips appear with the switch.
        tapSwitchNextTo("Weekly balance check")
        waitForSwitch("Weekly balance check", true)
        waitForText("Remind me on")
        tapText("Wed")
        waitFor("Wednesday to be saved") { appSettingsNow().reconcileReminderDay == DayOfWeek.WEDNESDAY }
        shot("settings-05-reminders-on")
        tapSwitchNextTo("Weekly balance check")
        waitForSwitch("Weekly balance check", false)

        // Forecast options.
        waitForText("Simulations")
        scrollIntoView(txt("Simulations"))
        tapText("500")
        waitForExists(hasText("500") and isSelected(), "500 simulations selected")
        waitFor("simulations to be saved") { budgetSettingsNow().simulations == 500 }
        tapText("13 weeks")
        waitFor("the horizon to be saved") { budgetSettingsNow().forecastHorizonDays == 91 }
        val start = today.minusDays(20)
        assertFieldValue("Date", "Not set")
        pickDate("Date", shown = today, target = start)
        waitFor("the history start date to be saved") { budgetSettingsNow().historyStartDate == start }
        shot("settings-06-forecast-options")
        tapText("Clear")
        assertFieldValue("Date", "Not set")
        waitFor("the history start date to be cleared") { budgetSettingsNow().historyStartDate == null }

        // Home still works after all of that.
        tapBack()
        waitForExists(hasTestTag(YOUR_MONEY_TAG), "Home after changing settings")
        waitFor("a safe-to-spend figure", HEAVY_TIMEOUT_MS) { (safeToSpendPerWeek() ?: 0L) > 0L }
    }

    @Test
    fun overdraftZeroPointAndBufferChangeWhatHomeShows() {
        val budget = seedStandardBudget()
        launchHome()
        expectYourMoney(budget.total)
        openSettings()
        assertFieldValue("Safety buffer (£)", "100")

        // "GBP 0 = overdraft used up" adds each account's own overdraft to what is shown.
        tapText("£0 = overdraft used up")
        waitFor("the zero point to be saved") { budgetSettingsNow().zeroPoint == ZeroPoint.OVERDRAFT_LIMIT }
        type("Safety buffer (£)", "250")
        waitFor("the buffer to be saved") { budgetSettingsNow().buffer == Money.pounds(250) }
        shot("settings-07-overdraft-zero-point")
        tapBack()
        expectYourMoney(gbp(budget.totalPence + 150_000))
        waitForText("above your overdraft limit")
        expectAccountRow("Lloyds current", gbp(budget.lloydsPence + 150_000))
        expectAccountRow("Santander Savings", "£400.00")
        waitForText("above overdraft", substring = true)
        shot("home-08-overdraft-zero-point")

        // The account detail shows both the shown and the raw balance.
        tap(hasText("Lloyds current") and hasText(gbp(budget.lloydsPence + 150_000)) and hasClickAction(), "the Lloyds row")
        waitForText("Raw balance: £6,579.30")
        shot("home-09-detail-with-raw-balance")
        tapBack()

        // And back to the bank's view.
        openSettings()
        tapText("£0 = empty account")
        waitFor("the zero point to be saved") { budgetSettingsNow().zeroPoint == ZeroPoint.ACCOUNT_BALANCE }
        tapBack()
        expectYourMoney(budget.total)
    }

    @Test
    fun aboutShowsWhenAndWhatTheForecastLastComputed() {
        val budget = seedStandardBudget()
        launchHome()
        expectYourMoney(budget.total)
        openSettings()
        waitForText("Version", substring = true)
        waitForText("Forecast updated", substring = true, timeoutMs = HEAVY_TIMEOUT_MS)
        scrollIntoView(txt("Forecast updated", substring = true))
        val line = firstText(txt("Forecast updated", substring = true)).orEmpty()
        assertTrue("About says: $line", Regex("Forecast updated [0-9]{2}:[0-9]{2}:[0-9]{2} for ").containsMatchIn(line))
        assertTrue("About says: $line", line.contains(" for ${budget.today.asHeaderText()}"))
        assertTrue("About says: $line", line.contains("3 accounts"))
        assertTrue("About says: $line", line.endsWith("total ${budget.total}"))
        shot("settings-08-about")
    }

    @Test
    fun backupAndStatementImportScreensOpen() {
        val budget = seedStandardBudget()
        launchHome()
        expectYourMoney(budget.total)
        openSettings()

        tapText("Backup & restore")
        waitForText("You've never exported a backup.")
        waitForExists(hasText("Export backup") and hasClickAction(), "the Export backup button")
        waitForExists(hasText("Choose backup file") and hasClickAction(), "the Choose backup file button")
        waitForText("Replaces everything currently in the app", substring = true)
        shot("settings-09-backup")
        tapBack()

        // The import wizard starts on the account picker (the default account is preselected). The
        // system file picker is not opened here.
        tapText("Import statement")
        waitForText("Import a bank statement")
        waitForText("Choose a CSV export from your bank", substring = true)
        assertFieldValue("Account", "Lloyds current")
        waitForExists(hasText("Choose file") and hasClickAction() and isEnabled(), "the Choose file button")
        choose("Account", "Santander current")
        assertFieldValue("Account", "Santander current")
        shot("settings-10-statement-import")
        tapBack()
        waitForText("Money display")
    }

    @Test
    fun categoriesCanBeAddedRenamedArchivedAndRestored() {
        seedStandardBudget()
        launchHome()
        openSettings()
        tapText("Categories")
        waitForText("Out")
        waitForText("In")
        waitForText("System")
        waitForText("Groceries")
        waitForText("Untracked")
        shot("settings-11-categories")

        // The system category can only change colour.
        tapIcon("Edit Untracked")
        waitForText("Edit category")
        waitForText("The Untracked category can't be renamed or removed; you can change its colour.")
        shot("settings-12-untracked-edit")
        tapInDialog("Cancel")
        waitForNoText("Edit category")

        // Add one.
        tapIcon("Add category")
        waitForText("Add category")
        waitForExists(hasText("Save") and isNotEnabled(), "Save disabled while the name is empty")
        type("Name", "Coffee shops")
        shot("settings-13-add-category")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the category dialog")
        waitForText("Coffee shops")

        // Rename it.
        tapIcon("Edit Coffee shops")
        waitForText("Edit category")
        type("Name", "Coffee")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the category dialog")
        waitForText("Coffee")
        waitForNoText("Coffee shops")

        // Archive, then restore.
        val editCoffee = hasContentDescription("Edit Coffee") and hasClickAction()
        tapNearestTo(editCoffee, clickableText("Archive"), "Coffee's Archive button")
        waitForText("Archived")
        shot("settings-14-category-archived")
        tapNearestTo(editCoffee, clickableText("Restore"), "Coffee's Restore button")
        waitForNoText("Archived")
    }

    @Test
    fun presetsCanBeAddedReorderedAndDeleted() {
        seedStandardBudget()
        launchHome()
        openSettings()
        tapText("Presets")
        waitForText("No presets yet", substring = true)
        shot("settings-15-presets-empty")

        tapIcon("Add preset")
        waitForText("Add preset")
        waitForExists(hasText("Save") and isNotEnabled(), "Save disabled until the preset is complete")
        type("Label", "Meal deal")
        type("Amount", "3.85")
        choose("Category", "Eating out")
        waitForText("Use default account (Lloyds current)")
        shot("settings-16-add-preset")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the preset dialog")
        waitForText("Out · £3.85 · Eating out · Default account (Lloyds current)")

        // A second one, logged against a specific account.
        tapIcon("Add preset")
        waitForText("Add preset")
        type("Label", "Bus")
        type("Amount", "2.10")
        choose("Category", "Transport")
        choose("Or a specific account", "Santander current")
        tapInDialog("Save")
        waitUntilGone(dialogButton("Save"), "the preset dialog")
        waitForText("Out · £2.10 · Transport · Santander current")
        assertTrue("Meal deal is listed first", topOf(txt("Meal deal")) < topOf(txt("Bus")))

        // Move the second one up: the order swaps.
        tapIcon("Move up")
        waitFor("Bus to move above Meal deal") { topOf(txt("Bus")) < topOf(txt("Meal deal")) }
        shot("settings-17-presets-reordered")

        // Delete Meal deal, after confirming.
        tapNearestTo(hasText("Meal deal"), hasContentDescription("Delete") and hasClickAction(), "Meal deal's Delete icon")
        waitForText("Delete preset?")
        shot("settings-18-delete-preset")
        tapInDialog("Delete")
        waitForNoText("Meal deal")
        waitForText("Bus")
    }
}
