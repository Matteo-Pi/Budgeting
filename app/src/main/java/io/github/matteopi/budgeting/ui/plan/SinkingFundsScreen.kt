package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Sinking funds list: reserved so far / target, weekly contribution (WP-U3). */
@Composable
fun SinkingFundsScreen(
    onBack: () -> Unit,
    onOpenFund: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Sinking funds", onBack = onBack, modifier = modifier)
}
