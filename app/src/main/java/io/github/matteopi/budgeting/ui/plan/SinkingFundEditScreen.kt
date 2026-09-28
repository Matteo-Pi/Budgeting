package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Add/edit a sinking fund: name, target, start/target dates, category (WP-U3). */
@Composable
fun SinkingFundEditScreen(
    fundId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = if (fundId == null) "Add fund" else "Edit fund", onBack = onBack, modifier = modifier)
}
