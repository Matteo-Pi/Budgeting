package io.github.matteopi.budgeting.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Export/restore JSON backup, CSV export (WP-U5). */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Backup", onBack = onBack, modifier = modifier)
}
