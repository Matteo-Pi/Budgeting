package io.github.matteopi.budgeting.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** The Activity tab: all transactions grouped by day, with filters and an edit sheet (WP-U2). */
@Composable
fun ActivityScreen(
    onAddTransaction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Activity", modifier = modifier)
}
