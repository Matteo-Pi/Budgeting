package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Add/edit a recurring rule: label, direction, amount, account, frequency, posting mode (WP-U3). */
@Composable
fun RecurringEditScreen(
    ruleId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = if (ruleId == null) "Add recurring" else "Edit recurring", onBack = onBack, modifier = modifier)
}
