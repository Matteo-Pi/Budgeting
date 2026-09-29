package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.SeededBudget
import io.github.matteopi.budgeting.testing.UI_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.seedPreset
import io.github.matteopi.budgeting.testing.seedScenario
import io.github.matteopi.budgeting.testing.seedSinkingFund
import io.github.matteopi.budgeting.testing.seedStandardBudget
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: on a seeded app, open every destination (each tab, and every screen reachable from
 * them by their buttons), check something characteristic of it is shown - a crash fails the test -
 * and take a screenshot of each.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest : UiTest() {

    private var shots = 0

    /** Seeds the standard budget plus one of everything the detail screens list. */
    private fun seedEverything(): SeededBudget {
        val budget = seedStandardBudget()
        val today = LocalDate.now()
        seedPreset("Meal deal", 385, CategoryFlow.OUT, "Eating out")
        seedSinkingFund("Interrail", 60_000, today, today.plusMonths(3))
        seedScenario(
            Scenario(
                name = "Buy a laptop",
                items = listOf(
                    ScenarioItem(
                        type = ScenarioItemType.ONE_OFF,
                        label = "Laptop",
                        amount = Money(-80_000),
                        date = today.plusDays(14),
                    ),
                ),
            ),
        )
        return budget
    }

    private fun snap(slug: String) {
        shots += 1
        shot("nav-" + shots.toString().padStart(2, '0') + "-" + slug)
    }

    private fun arrive(slug: String, hallmark: String, timeoutMs: Long = UI_TIMEOUT_MS, substring: Boolean = false) {
        waitForText(hallmark, substring = substring, timeoutMs = timeoutMs)
        snap(slug)
    }

    private fun backTo(hallmark: String, substring: Boolean = false) {
        tapBack()
        waitForText(hallmark, substring = substring, timeoutMs = HEAVY_TIMEOUT_MS)
    }

    private fun startOnHome() {
        val budget = seedEverything()
        launchHome()
        expectYourMoney(budget.total)
    }

    @Test
    fun bottomBarTabsAllOpen() {
        startOnHome()
        arrive("home", "Recent activity")
        waitForExists(hasText("Home") and isSelected(), "Home selected in the bottom bar")

        goToTab("Activity")
        arrive("activity", "Yesterday")
        waitForExists(hasText("Activity") and isSelected(), "Activity selected in the bottom bar")

        goToTab("Forecast")
        arrive("forecast", "Budget periods", HEAVY_TIMEOUT_MS)
        waitForExists(hasText("Forecast") and isSelected(), "Forecast selected in the bottom bar")

        goToTab("Plan")
        arrive("plan", "Today: term (Autumn)", HEAVY_TIMEOUT_MS)
        waitForExists(hasText("Plan") and isSelected(), "Plan selected in the bottom bar")

        goToTab("Insights")
        arrive("insights", "Weekly spending")
        waitForExists(hasText("Insights") and isSelected(), "Insights selected in the bottom bar")

        // Tabs keep their place: going back to Home shows the same figures.
        goToTab("Home")
        waitForExists(hasTestTag(YOUR_MONEY_TAG), "Home again")
        arrive("home-again", "Recent activity")
    }

    @Test
    fun homeAndAccountDestinations() {
        startOnHome()

        // Settings and back.
        openSettings()
        arrive("settings", "Money display")
        backTo("Recent activity")

        // An account: detail, then its edit screen.
        expectAccountRow("Lloyds current", "£6,579.30")
        tap(hasText("Lloyds current") and hasText("£6,579.30") and hasClickAction(), "the Lloyds row")
        waitForExists(hasTestTag(ACCOUNT_BALANCE_TAG), "the account detail")
        arrive("account-detail", "Reconciliation history")
        tapIcon("Edit account")
        arrive("account-edit", "Edit account")
        backTo("Reconciliation history")
        backTo("Recent activity")

        // A new account form from the Accounts card.
        tapText("Add account")
        arrive("account-add", "Add account")
        backTo("Recent activity")

        // The + button opens the quick-add card in its own window; Back returns.
        tapIcon("Add transaction")
        waitForText("£0")
        snap("quick-add")
        pressSystemBack()
        waitForExists(hasTestTag(YOUR_MONEY_TAG), "Home after quick add")

        // Recent activity's "See all" goes to the Activity tab.
        tapText("See all")
        arrive("activity-from-home", "Yesterday")
        // Activity's own buttons: search, filters, add.
        tapIcon("Search")
        arrive("activity-search", "Search label")
        tapIcon("Close search")
        tapText("Category")
        waitForExists(menuItem("Groceries"), "the category filter menu")
        snap("activity-category-menu")
        tapMenuItem("All")
        tapIcon("Add transaction")
        waitForText("£0")
        pressSystemBack()
        waitForText("Yesterday")
    }

    @Test
    fun forecastDestinations() {
        startOnHome()
        goToTab("Forecast")
        waitForText("Budget periods", timeoutMs = HEAVY_TIMEOUT_MS)

        tapText("Spending estimates")
        arrive("predictions", "Groceries", HEAVY_TIMEOUT_MS)
        backTo("Budget periods")

        tapText("What-if scenarios")
        arrive("scenarios", "Buy a laptop")
        tap(hasText("Buy a laptop") and hasClickAction(), "the scenario row")
        arrive("scenario-edit", "Edit scenario")
        waitForText("Laptop")
        backTo("Buy a laptop")
        tapIcon("New scenario")
        arrive("scenario-new", "Scenario name")
        backTo("Buy a laptop")
        backTo("Budget periods")

        tapText("Timeline")
        arrive("timeline", "Week of", HEAVY_TIMEOUT_MS, substring = true)
        backTo("Budget periods")

        tapText("Accuracy")
        arrive("accuracy", "Accuracy appears after a week of data.", HEAVY_TIMEOUT_MS)
        backTo("Budget periods")
    }

    @Test
    fun planDestinations() {
        startOnHome()
        goToTab("Plan")
        waitForText("Today: term (Autumn)", timeoutMs = HEAVY_TIMEOUT_MS)

        // Recurring rules: list, an existing rule's editor, a new rule's editor.
        tapText("Regular costs")
        arrive("recurring-list", "Regular money")
        waitForText("Rent")
        tap(hasText("Rent") and hasClickAction(), "the Rent rule")
        arrive("recurring-edit", "Edit recurring")
        backTo("Regular money")
        tapIcon("Add recurring rule")
        arrive("recurring-add", "Add recurring")
        backTo("Regular money")
        backTo("Today: term (Autumn)")

        // One-off dates.
        tapText("One-off dates")
        arrive("scheduled-list", "Upcoming (2)")
        tap(hasText("Student loan") and hasClickAction(), "a student loan instalment", index = 0)
        arrive("scheduled-edit", "Edit one-off")
        backTo("Upcoming (2)")
        tapIcon("Add one-off date")
        arrive("scheduled-add", "Add one-off")
        backTo("Upcoming (2)")
        backTo("Today: term (Autumn)")

        tapText("Term dates")
        arrive("term-dates", "Autumn (current)")
        tapIcon("Add term")
        arrive("term-add-dialog", "Add term")
        tapInDialog("Cancel")
        backTo("Today: term (Autumn)")

        tapText("Student loan helper")
        arrive("student-loan-helper", "Instalment 1")
        backTo("Today: term (Autumn)")

        tapText("Sinking funds")
        arrive("sinking-funds", "Interrail", HEAVY_TIMEOUT_MS)
        tap(hasText("Interrail") and hasClickAction(), "the Interrail fund")
        arrive("sinking-fund-edit", "Edit fund")
        backTo("Interrail")
        tapIcon("Add sinking fund")
        arrive("sinking-fund-add", "Add fund")
        backTo("Interrail")
        backTo("Today: term (Autumn)")

        tapText("Money in")
        arrive("money-in", "Regular money")
        backTo("Today: term (Autumn)")

        tapText("Spending estimates")
        arrive("predictions-from-plan", "Groceries", HEAVY_TIMEOUT_MS)
        backTo("Today: term (Autumn)")
    }

    @Test
    fun settingsDestinations() {
        startOnHome()
        openSettings()

        tapText("Categories")
        arrive("categories", "Untracked")
        backTo("Money display")

        tapText("Presets")
        arrive("presets", "Meal deal")
        backTo("Money display")

        tapText("Backup & restore")
        arrive("backup", "You've never exported a backup.")
        backTo("Money display")

        tapText("Import statement")
        arrive("statement-import", "Import a bank statement")
        backTo("Money display")

        // The rest of Settings on one long screen.
        scrollIntoView(txt("Simulations"))
        snap("settings-forecast")
        scrollIntoView(txt("Export CSV"))
        snap("settings-data")
        waitForText("Forecast updated", substring = true, timeoutMs = HEAVY_TIMEOUT_MS)
        scrollIntoView(txt("Forecast updated", substring = true))
        snap("settings-about")
    }
}
