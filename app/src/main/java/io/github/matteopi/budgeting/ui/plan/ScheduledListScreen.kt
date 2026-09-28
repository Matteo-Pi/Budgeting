package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** One-off scheduled items (specific future dates) list (WP-U3). */
@Composable
fun ScheduledListScreen(
    onBack: () -> Unit,
    onOpenScheduled: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "One-off dates", onBack = onBack, modifier = modifier)
}
