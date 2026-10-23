package io.github.matteopi.budgeting.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.AppIcons

/**
 * The action that moves the app to another section, given to every screen inside the NavHost by
 * `BudgetNavHost` (docs/PLAN.md §11.8). It is null outside the NavHost (previews, tests of a single
 * screen), where [SectionSwitchButton] then draws nothing.
 */
val LocalSectionSwitch = compositionLocalOf<((AppSection) -> Unit)?> { null }

/** A section's name and icon, as the chooser shows them. */
val AppSection.title: String
    get() = when (this) {
        AppSection.BUDGETING -> "Budgeting"
        AppSection.RECIPES -> "Food"
        AppSection.PLANNER -> "Planner"
    }

val AppSection.icon: ImageVector
    get() = when (this) {
        AppSection.BUDGETING -> AppIcons.Wallet
        AppSection.RECIPES -> AppIcons.Restaurant
        AppSection.PLANNER -> AppIcons.EventAvailable
    }

/**
 * The section chooser, for the `navigationIcon` slot of the `TopAppBar` of each tab screen (docs/PLAN.md §13.8).
 * It shows the icon of [section] (the section the screen belongs to) and is described as *Switch section*; it
 * opens a menu of the other two sections ("Switch to Food", …). Sub-screens keep their back arrow instead.
 */
@Composable
fun SectionSwitchButton(section: AppSection) {
    val switchTo = LocalSectionSwitch.current ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(section.icon, contentDescription = "Switch section")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppSection.entries.filter { it != section }.forEach { target ->
                DropdownMenuItem(
                    text = { Text(target.title) },
                    leadingIcon = { Icon(target.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        switchTo(target)
                    },
                    modifier = Modifier.semantics { contentDescription = "Switch to ${target.title}" },
                )
            }
        }
    }
}
