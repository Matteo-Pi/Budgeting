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
 * The two sections (docs/PLAN.md §11.8): the switch at the top left of every tab moves between Budgeting and
 * Recipes, each with its own bottom bar; the last section is remembered; Back on a section's first tab leaves
 * the app. The Recipes tabs are placeholders for now, so these tests look at the bars and the switch, not at
 * what the tabs show.
 */
@RunWith(AndroidJUnit4::class)
class SectionSwitchTest : UiTest() {

    private val budgetingTabs = listOf("Home", "Activity", "Forecast", "Plan", "Insights")
    private val recipesTabs = listOf("Recipes", "Compare", "Shopping", "Ingredients")

    private fun switchButton(description: String) = hasContentDescription(description) and hasClickAction()

    /** The section the app has saved as the one to open next time. */
    private fun savedSection(): AppSection = blocking { appContainer.settingsStore.appSection.first() }

    private fun expectBudgetingSection(timeoutMs: Long = UI_TIMEOUT_MS) {
        waitForExists(switchButton("Switch to Recipes"), "the switch to Recipes", timeoutMs)
        budgetingTabs.forEach { waitForExists(clickableText(it), "the '$it' tab", timeoutMs) }
        recipesTabs.forEach { waitUntilGone(clickableText(it), "the Recipes tab '$it'", timeoutMs) }
        assertNeverShows(switchButton("Switch to Budgeting"), "The switch to Budgeting", forMs = 300)
    }

    private fun expectRecipesSection(timeoutMs: Long = UI_TIMEOUT_MS) {
        waitForExists(switchButton("Switch to Budgeting"), "the switch to Budgeting", timeoutMs)
        recipesTabs.forEach { waitForExists(clickableText(it), "the '$it' tab", timeoutMs) }
        budgetingTabs.forEach { waitUntilGone(clickableText(it), "the Budgeting tab '$it'", timeoutMs) }
        assertNeverShows(switchButton("Switch to Recipes"), "The switch to Recipes", forMs = 300)
    }

    private fun expectSelectedTab(name: String) =
        waitForExists(hasText(name) and isSelected(), "the '$name' tab selected in the bottom bar")

    @Test
    fun theSwitchMovesBetweenTheTwoSectionsAndEachHasItsOwnTabs() {
        seedSmallLedger()
        launchHome()

        // Budgeting: the five tabs, each with the switch to Recipes at the top left.
        expectBudgetingSection()
        expectSelectedTab("Home")
        shot("sections-01-budgeting-home")
        for (tab in budgetingTabs.drop(1) + "Home") {
            goToTab(tab)
            waitForExists(switchButton("Switch to Recipes"), "the switch on the $tab tab", HEAVY_TIMEOUT_MS)
        }
        goToTab("Insights")

        // To Recipes: four tabs, no Budgeting ones, and the Recipes tab is open.
        tapIcon("Switch to Recipes")
        expectRecipesSection()
        expectSelectedTab("Recipes")
        shot("sections-02-recipes")

        // Every Recipes tab has the switch back to Budgeting.
        listOf("Compare" to "sections-03-compare", "Shopping" to "sections-04-shopping", "Ingredients" to "sections-05-ingredients")
            .forEach { (tab, shotName) ->
                goToTab(tab)
                waitForExists(switchButton("Switch to Budgeting"), "the switch on the $tab tab")
                shot(shotName)
            }
        goToTab("Recipes")
        waitForExists(switchButton("Switch to Budgeting"), "the switch on the Recipes tab")

        // A sub-screen keeps the Recipes bar but has a back arrow instead of the switch.
        tapIcon("Recipe settings")
        waitUntilGone(switchButton("Switch to Budgeting"), "the switch on a sub-screen")
        recipesTabs.forEach { waitForExists(clickableText(it), "the '$it' tab on a sub-screen") }
        shot("sections-06-recipes-sub-screen")
        tapBack()
        waitForExists(switchButton("Switch to Budgeting"), "the Recipes tab after Back")

        // Back to Budgeting: Home (not the tab that was open before), with the five tabs.
        tapIcon("Switch to Budgeting")
        expectBudgetingSection()
        expectSelectedTab("Home")
        shot("sections-07-budgeting-again")
    }

    @Test
    fun theLastSectionOpensNextTime() {
        seedSmallLedger()
        val first = launchMain()
        waitForExists(switchButton("Switch to Recipes"), "Home's switch to Recipes", HEAVY_TIMEOUT_MS)
        assertEquals("Budgeting is the section until the user switches", AppSection.BUDGETING, savedSection())

        tapIcon("Switch to Recipes")
        expectRecipesSection()
        waitFor("the Recipes section to be saved") { savedSection() == AppSection.RECIPES }

        // Closing and launching again opens on Recipes, not Home.
        first.close()
        val second = launchMain()
        expectRecipesSection(HEAVY_TIMEOUT_MS)
        expectSelectedTab("Recipes")
        shot("sections-08-reopened-on-recipes")

        // And back: the choice is saved again, and the next launch opens on Home.
        tapIcon("Switch to Budgeting")
        expectBudgetingSection()
        waitFor("the Budgeting section to be saved") { savedSection() == AppSection.BUDGETING }
        second.close()
        launchMain()
        expectBudgetingSection(HEAVY_TIMEOUT_MS)
        expectSelectedTab("Home")
        shot("sections-09-reopened-on-budgeting")
    }

    @Test
    fun backOnTheRecipesTabLeavesTheApp() {
        seedSmallLedger()
        val scenario = launchMain()
        waitForExists(switchButton("Switch to Recipes"), "Home's switch to Recipes", HEAVY_TIMEOUT_MS)
        tapIcon("Switch to Recipes")
        expectRecipesSection()

        // Back from another Recipes tab goes to the Recipes tab (the back stack holds only those two) ...
        goToTab("Compare")
        pressSystemBack()
        expectSelectedTab("Recipes")
        waitForExists(switchButton("Switch to Budgeting"), "the Recipes tab after Back")

        // ... and Back from the Recipes tab leaves the app instead of reaching the Budgeting tabs that were
        // open before the switch. From Android 12 a launcher activity that is the root of its task is moved
        // to the back rather than destroyed, so "left" means no longer in the foreground.
        pressSystemBack()
        waitFor("the app to be left after Back on the Recipes tab") { scenario.state.name != "RESUMED" }
    }
}
