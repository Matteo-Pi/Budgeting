package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Term-aware weekly calendar of committed and expected flows (WP-U4). */
@Composable
fun TimelineScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Timeline", onBack = onBack, modifier = modifier)
}
