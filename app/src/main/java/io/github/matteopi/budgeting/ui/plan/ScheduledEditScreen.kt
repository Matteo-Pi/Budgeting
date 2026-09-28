package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Add/edit a one-off scheduled item (WP-U3). */
@Composable
fun ScheduledEditScreen(
    itemId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = if (itemId == null) "Add one-off" else "Edit one-off", onBack = onBack, modifier = modifier)
}
