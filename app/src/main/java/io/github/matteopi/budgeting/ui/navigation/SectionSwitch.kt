package io.github.matteopi.budgeting.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.AppIcons

/**
 * The action that moves the app to another section, given to every screen inside the NavHost by
 * `BudgetNavHost` (docs/PLAN.md §11.8). It is null outside the NavHost (previews, tests of a single
 * screen), where [SectionSwitchButton] then draws nothing.
 */
val LocalSectionSwitch = compositionLocalOf<((AppSection) -> Unit)?> { null }

/**
 * The switch between Budgeting and Recipes, for the `navigationIcon` slot of the `TopAppBar` of each tab
 * screen. [section] is the section the screen belongs to: Budgeting's tabs show a fork and knife
 * ("Switch to Recipes") and Recipes' tabs a wallet ("Switch to Budgeting"). Sub-screens keep their back
 * arrow instead.
 */
@Composable
fun SectionSwitchButton(section: AppSection) {
    val switchTo = LocalSectionSwitch.current ?: return
    val target = section.other
    val (icon, description) = when (target) {
        AppSection.RECIPES -> AppIcons.Restaurant to "Switch to Recipes"
        AppSection.BUDGETING -> AppIcons.Wallet to "Switch to Budgeting"
    }
    IconButton(onClick = { switchTo(target) }) {
        Icon(icon, contentDescription = description)
    }
}
