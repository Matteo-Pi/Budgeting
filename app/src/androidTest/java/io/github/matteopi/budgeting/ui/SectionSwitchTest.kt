package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UI_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.seedSmallLedger
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three sections (docs/PLAN.md §11.8, §13.8): the chooser at the top left of every tab moves between Budgeting,
 * Food (recipes, the diary and the kitchen) and the Planner, each with its own bottom bar; the last section is remembered; Back on a section's first
 * tab leaves the app. These tests look at the bars, the chooser and the settings actions, not at what the tabs show.
 */
@RunWith(AndroidJUnit4::class)
class SectionSwitchTest : UiTest() {

    // "Insights" is a tab of both Budgeting and the Planner, so only the other tabs tell the sections apart.
    private val budgetingTabs = listOf("Home", "Activity", "Forecast", "Plan", "Insights")
    private val budgetingOnly = listOf("Home", "Activity", "Forecast", "Plan")
    private val recipesTabs = listOf("Recipes", "Diary", "Kitchen", "Shopping", "Ingredients")
    private val plannerTabs = listOf("Today", "Timetable", "To-do", "Habits", "Insights")
    private val plannerOnly = listOf("Today", "Timetable", "To-do", "Habits")

    private val chooser = hasContentDescription("Switch section") and hasClickAction()

    /** The section the app has saved as the one to open next time. */
    private fun savedSection(): AppSection = blocking { appContainer.settingsStore.appSection.first() }

    private fun switchTo(section: String) {
        tap(chooser, "the section chooser")
        tapMenuItem(section)
    }

    private fun expectOnly(tabs: List<String>, notThese: List<String>, timeoutMs: Long = UI_TIMEOUT_MS) {
        waitForExists(chooser, "the section chooser", timeoutMs)
        tabs.forEach { waitForExists(clickableText(it), "the '$it' tab", timeoutMs) }
        notThese.forEach { waitUntilGone(clickableText(it), "the other section's tab '$it'", timeoutMs) }
    }

    private fun expectBudgetingSection(timeoutMs: Long = UI_TIMEOUT_MS) = expectOnly(budgetingTabs, recipesTabs + plannerOnly, timeoutMs)

    private fun expectRecipesSection(timeoutMs: Long = UI_TIMEOUT_MS) = expectOnly(recipesTabs, budgetingTabs + plannerOnly, timeoutMs)

    private fun expectPlannerSection(timeoutMs: Long = UI_TIMEOUT_MS) = expectOnly(plannerTabs, budgetingOnly + recipesTabs, timeoutMs)

    private fun expectSelectedTab(name: String) =
        waitForExists(hasText(name) and isSelected(), "the '$name' tab selected in the bottom bar")

    @Test
    fun theChooserMovesBetweenTheThreeSectionsAndEachHasItsOwnTabs() {
        seedSmallLedger()
        launchHome()

        // Budgeting: the five tabs, each with the chooser at the top left.
        expectBudgetingSection()
        expectSelectedTab("Home")
        shot("sections-01-budgeting-home")
        for (tab in budgetingTabs.drop(1) + "Home") {
            goToTab(tab)
            waitForExists(chooser, "the chooser on the $tab tab", HEAVY_TIMEOUT_MS)
        }

        // The chooser offers the other two sections.
        tap(chooser, "the section chooser")
        waitForExists(menuItem("Food"), "Food in the chooser")
        waitForExists(menuItem("Planner"), "the Planner in the chooser")
        assertNeverShows(menuItem("Budgeting"), "Budgeting offered from inside Budgeting", forMs = 300)
        shot("sections-02-chooser")
        tapMenuItem("Food")

        // Food: five tabs, none of the others, and the Recipes tab is open.
        expectRecipesSection()
        expectSelectedTab("Recipes")
        shot("sections-03-recipes")
        for (tab in recipesTabs.drop(1) + "Recipes") {
            goToTab(tab)
            waitForExists(chooser, "the chooser on the $tab tab")
        }

        // A sub-screen keeps the bar but has a back arrow instead of the chooser.
        tapIcon("Food settings")
        waitUntilGone(chooser, "the chooser on a sub-screen")
        recipesTabs.forEach { waitForExists(clickableText(it), "the '$it' tab on a sub-screen") }
        tapBack()
        waitForExists(chooser, "the Recipes tab after Back")

        // The Planner: five tabs, Today first, each with the chooser and the gear for the Planner's settings.
        switchTo("Planner")
        expectPlannerSection()
        expectSelectedTab("Today")
        shot("sections-04-planner-today")
        for (tab in plannerTabs.drop(1) + "Today") {
            goToTab(tab)
            waitForExists(chooser, "the chooser on the $tab tab")
            waitForExists(hasContentDescription("Planner settings") and hasClickAction(), "the gear on the $tab tab")
        }
        tapIcon("Planner settings")
        waitForText("Planner settings")
        waitUntilGone(chooser, "the chooser on the Planner's settings")
        plannerOnly.forEach { waitForExists(clickableText(it), "the '$it' tab on the Planner's settings") }
        shot("sections-05-planner-settings")
        tapBack()

        // Back to Budgeting: Home (not the tab that was open before), with the five tabs.
        switchTo("Budgeting")
        expectBudgetingSection()
        expectSelectedTab("Home")
        shot("sections-06-budgeting-again")
    }

    @Test
    fun theLastSectionOpensNextTime() {
        seedSmallLedger()
        val first = launchMain()
        waitForExists(chooser, "Home's section chooser", HEAVY_TIMEOUT_MS)
        assertEquals("Budgeting is the section until the user switches", AppSection.BUDGETING, savedSection())

        switchTo("Food")
        expectRecipesSection()
        waitFor("the Recipes section to be saved") { savedSection() == AppSection.RECIPES }

        // Closing and launching again opens on Recipes, not Home.
        first.close()
        val second = launchMain()
        expectRecipesSection(HEAVY_TIMEOUT_MS)
        expectSelectedTab("Recipes")
        shot("sections-07-reopened-on-recipes")

        // The Planner is remembered the same way.
        switchTo("Planner")
        expectPlannerSection()
        waitFor("the Planner to be saved") { savedSection() == AppSection.PLANNER }
        second.close()
        val third = launchMain()
        expectPlannerSection(HEAVY_TIMEOUT_MS)
        expectSelectedTab("Today")
        shot("sections-08-reopened-on-planner")

        // And back: the next launch opens on Home.
        switchTo("Budgeting")
        expectBudgetingSection()
        waitFor("the Budgeting section to be saved") { savedSection() == AppSection.BUDGETING }
        third.close()
        launchMain()
        expectBudgetingSection(HEAVY_TIMEOUT_MS)
        expectSelectedTab("Home")
    }

    @Test
    fun backOnASectionsFirstTabLeavesTheApp() {
        seedSmallLedger()
        val scenario = launchMain()
        waitForExists(chooser, "Home's section chooser", HEAVY_TIMEOUT_MS)
        switchTo("Food")
        expectRecipesSection()

        // Back from another Food tab goes to the Recipes tab (the back stack holds only those two) ...
        goToTab("Diary")
        pressSystemBack()
        expectSelectedTab("Recipes")
        waitForExists(chooser, "the Recipes tab after Back")

        // ... and Back from the Recipes tab leaves the app instead of reaching the Budgeting tabs that were open
        // before the switch. From Android 12 a launcher activity that is the root of its task is moved to the back
        // rather than destroyed, so "left" means no longer in the foreground.
        pressSystemBack()
        waitFor("the app to be left after Back on the Recipes tab") { scenario.state.name != "RESUMED" }
    }

    @Test
    fun backOnTodayLeavesTheApp() {
        seedSmallLedger()
        val scenario = launchMain()
        waitForExists(chooser, "Home's section chooser", HEAVY_TIMEOUT_MS)
        switchTo("Planner")
        expectPlannerSection()

        goToTab("To-do")
        pressSystemBack()
        expectSelectedTab("Today")
        pressSystemBack()
        waitFor("the app to be left after Back on Today") { scenario.state.name != "RESUMED" }
    }
}
