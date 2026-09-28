package io.github.matteopi.budgeting.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Money display, forecast, categories, presets, privacy, reminders, data and about (WP-U5). */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenStatementImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Settings", onBack = onBack, modifier = modifier)
}
