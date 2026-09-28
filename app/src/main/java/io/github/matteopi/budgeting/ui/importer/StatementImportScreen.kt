package io.github.matteopi.budgeting.ui.importer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Statement import wizard: file + account, column mapping, review, categorise, import (WP-U5). */
@Composable
fun StatementImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Import statement", onBack = onBack, modifier = modifier)
}
