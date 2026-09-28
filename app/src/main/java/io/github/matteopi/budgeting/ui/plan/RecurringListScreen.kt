package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Recurring rules (subscriptions, rent, wages, ...) list (WP-U3). */
@Composable
fun RecurringListScreen(
    onBack: () -> Unit,
    onOpenRecurring: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Regular money", onBack = onBack, modifier = modifier)
}
