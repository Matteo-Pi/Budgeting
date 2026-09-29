package io.github.matteopi.budgeting.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.asShortText
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.seedStandardBudget
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Forecast (account views, horizons, timeline, what-if scenarios, accuracy) and Insights (periods,
 * account views), all on the standard three-account budget with twelve weeks of history.
 */
@RunWith(AndroidJUnit4::class)
class ForecastAndInsightsTest : UiTest() {

    private fun selectedChip(text: String) = hasText(text) and isSelected()

    private fun tapChip(text: String) {
        tapText(text)
        waitForExists(selectedChip(text), "the '$text' chip to be selected")
    }

    private fun openForecast() {
        launchHome()
        goToTab("Forecast")
        waitForText("Budget periods", timeoutMs = HEAVY_TIMEOUT_MS)
    }

    @Test
    fun forecastChartCanBeShownForAllAccountsOneAccountOrEachAccount() {
        openForecast()

        // Default: the pool total, which alone has the tier-1 baseline switch.
        waitForExists(selectedChip("All accounts"), "'All accounts' selected by default")
        waitForText("Show tier-1 baseline")
        assertTrue(!textExists("Across all accounts in safe-to-spend", substring = true))
        shot("forecast-01-all-accounts")

        // One account: the chart is that account only, and the risk sentence says it is about the pool.
        tapChip("Lloyds current")
        waitForText("Across all accounts in safe-to-spend", substring = true)
        waitForNoText("Show tier-1 baseline")
        shot("forecast-02-lloyds-current")

        tapChip("Santander Savings")
        waitForNoText("Show tier-1 baseline")
        shot("forecast-03-santander-savings")

        tapChip("Santander current")
        waitForNoText("Show tier-1 baseline")
        shot("forecast-04-santander-current")

        // Each account: one line per account, named in a legend under the chart (chip + legend entry).
        tapChip("Each account")
        waitFor("a legend entry for every account") {
            count(txt("Lloyds current")) >= 2 && count(txt("Santander Savings")) >= 2 && count(txt("Santander current")) >= 2
        }
        waitForNoText("Show tier-1 baseline")
        shot("forecast-05-each-account")

        // Back to the total.
        tapChip("All accounts")
        waitForText("Show tier-1 baseline")
        waitForNoText("Across all accounts in safe-to-spend", substring = true)
        shot("forecast-06-back-to-all-accounts")
    }

    @Test
    fun horizonChipsTier1SwitchBudgetPeriodsAndLinks() {
        openForecast()
        waitForExists(selectedChip("12 wks"), "'12 wks' selected by default")
        shot("forecast-07-horizon-12-weeks")
        tapChip("4 wks")
        shot("forecast-08-horizon-4-weeks")
        tapChip("Until next income")
        shot("forecast-09-horizon-until-next-income")
        tapChip("26 wks")
        shot("forecast-10-horizon-26-weeks")
        tapChip("12 wks")

        // The tier-1 baseline is an optional second line with its own legend entry.
        tap(isToggleable(), "the tier-1 switch")
        waitForText("Tier-1 baseline")
        shot("forecast-11-tier1-baseline")
        tap(isToggleable(), "the tier-1 switch")
        waitForNoText("Tier-1 baseline")

        // Lumpy income: a budget period per student-loan instalment.
        waitForText("Starting income")
        waitForText("Committed costs")
        waitForText("Available for spending")
        waitForText("Per week")
        shot("forecast-12-budget-periods")

        // Links to the deeper screens.
        waitForExists(hasText("Spending estimates") and hasClickAction(), "the Spending estimates link")
        waitForExists(hasText("What-if scenarios") and hasClickAction(), "the What-if link")
        waitForExists(hasText("Timeline") and hasClickAction(), "the Timeline link")
        waitForExists(hasText("Accuracy") and hasClickAction(), "the Accuracy link")
        shot("forecast-13-links")
    }

    @Test
    fun timelineListsWeeksWithTermsAndCommittedFlows() {
        openForecast()
        tapText("Timeline")
        waitForText("Week of", substring = true, timeoutMs = HEAVY_TIMEOUT_MS)
        waitForText("Term (Autumn)", substring = true)
        waitForText("· Rent", substring = true) // the monthly rent shows up in its week
        waitForText("Balance (end of week)")
        shot("timeline-01-all-accounts")

        // A week after Autumn and before Spring is a holiday week; the instalment shows in its week.
        waitForText("· Holiday", substring = true)
        waitForText("· Student loan", substring = true)
        shot("timeline-02-holiday-and-loan")

        // Per-account view: each week lists every account's balance.
        scrollIntoView(txt("Each account"))
        tapChip("Each account")
        waitForText("Lloyds current (end of week)")
        waitForText("Santander current (end of week)")
        shot("timeline-03-each-account")
    }

    @Test
    fun whatIfScenarioIsComparedAppliedAndDeleted() {
        val today = LocalDate.now()
        openForecast()
        tapText("What-if scenarios")
        waitForText("What-if")
        waitForText("No what-if scenarios yet", substring = true)
        shot("scenario-01-empty")

        tapIcon("New scenario")
        waitForText("New scenario")
        type("Scenario name", "Buy a laptop")

        // A one-off cost in two weeks.
        tapIcon("Add item")
        waitForExists(clickableText("One-off"), "the item editor")
        type("Label", "Laptop")
        type("Amount", "800")
        val due = today.plusDays(14)
        pickDate("Date", shown = today, target = due)
        shot("scenario-02-one-off-item")
        tap(clickableText("Save"), "Save in the item editor")
        waitForText("-£800.00 on ${due.asShortText()}")
        waitForText("Laptop")

        // And spending 20% less on groceries.
        tapIcon("Add item")
        tapText("Category %")
        choose("Category", "Groceries")
        type("Adjustment, e.g. -20 for 20% less", "-20")
        shot("scenario-03-category-adjust-item")
        tap(clickableText("Save"), "Save in the item editor")
        waitForText("Groceries -20% (term & holiday)")

        // The comparison against the unchanged forecast.
        waitForText("Comparison vs. baseline", timeoutMs = 2 * HEAVY_TIMEOUT_MS)
        waitForText("Safe to spend / week")
        waitForText("Balance at horizon end")
        waitForText("Chance below £0")
        val weekly = comparisonRow("Safe to spend / week")
        val end = comparisonRow("Balance at horizon end")
        shot("scenario-04-comparison")
        assertTrue("The scenario's safe-to-spend must not exceed the baseline: $weekly", weekly.second <= weekly.first + 100)
        assertTrue("A GBP 800 cost must lower the end balance: $end", end.second < end.first)

        // Save it; it is listed with its two items.
        tapIcon("Save scenario")
        waitForText("Buy a laptop")
        waitForText("2 items")
        shot("scenario-05-list")

        // Apply it to the plan: the one-off becomes a real scheduled item; the scenario stays.
        tap(hasText("Buy a laptop") and hasClickAction(), "the scenario row")
        waitForText("Edit scenario")
        tapText("Apply to plan")
        waitForText("Apply to plan?")
        tapInDialog("Apply")
        waitFor("the one-off to become a scheduled item", HEAVY_TIMEOUT_MS) {
            blocking { appContainer.planRepository.observeScheduledItems().first() }
                .any { it.label == "Laptop" && it.amount.pence == -80_000L && it.date == due }
        }
        shot("scenario-06-applied")
        tapBack()
        waitForText("Buy a laptop")

        // Delete the scenario (after confirming).
        tapIcon("Delete Buy a laptop")
        waitForText("Delete scenario?")
        tapInDialog("Delete")
        waitForText("No what-if scenarios yet", substring = true)
        shot("scenario-07-deleted")
    }

    @Test
    fun accuracyHasAnEmptyStateUntilThereIsHistory() {
        openForecast()
        tapText("Accuracy")
        waitForText("Accuracy appears after a week of data.", timeoutMs = HEAVY_TIMEOUT_MS)
        shot("accuracy-01-empty")
    }

    @Test
    fun insightsSwitchesPeriodsCategoriesAndAccountViews() {
        launchHome()
        goToTab("Insights")
        waitForText("Weekly spending")
        waitForExists(selectedChip("12 weeks"), "'12 weeks' selected by default")
        shot("insights-01-12-weeks")
        tapChip("6 months")
        shot("insights-02-6-months")
        tapChip("12 months")
        shot("insights-03-12-months")
        tapChip("12 weeks")

        waitForText("This month vs. average")
        waitForText("Term vs. holiday")
        waitForText("Balance history")
        waitForText("Income vs. spending")

        // Tapping a category opens its own weekly bars; tapping again closes them.
        val groceriesRow = hasText("Groceries") and hasClickAction()
        waitForExists(groceriesRow, "Groceries in this month vs. average")
        tap(groceriesRow, "the Groceries row")
        shot("insights-04-category-detail")
        tap(groceriesRow, "the Groceries row")
        waitForExists(groceriesRow, "the Groceries row after closing its detail")

        // Balance history: the same account views as the forecast chart.
        scrollIntoView(txt("Balance history"))
        waitForExists(selectedChip("All accounts"), "'All accounts' selected by default")
        shot("insights-05-balance-all")
        tapChip("Lloyds current")
        shot("insights-06-balance-lloyds")
        tapChip("Santander Savings")
        tapChip("Santander current")
        shot("insights-07-balance-santander")
        tapChip("Each account")
        waitFor("a legend entry per account") { count(txt("Santander Savings")) >= 2 }
        shot("insights-08-balance-each-account")
        tapChip("All accounts")
        scrollIntoView(txt("Income vs. spending"))
        shot("insights-09-income-vs-spending")
    }

    /** (baseline, scenario) in pence of one row of the scenario comparison card. */
    private fun comparisonRow(label: String): Pair<Long, Long> {
        val labelNode = compose.onAllNodes(hasText(label)).fetchSemanticsNodes().first()
        val siblings = labelNode.parent!!.children
        val index = siblings.indexOfFirst { it.id == labelNode.id }
        fun textAt(i: Int): String =
            siblings[i].config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        val baseline = textAt(index + 1)
        val arrow = textAt(index + 2)
        val scenario = textAt(index + 3)
        assertEquals("Expected 'baseline -> scenario' after '$label' but found $baseline / $arrow / $scenario", " → ", arrow)
        return parsePence(baseline) to parsePence(scenario)
    }

    private fun parsePence(text: String): Long =
        BigDecimal(text.replace("£", "").replace(",", "")).multiply(BigDecimal(100)).toLong()
}
