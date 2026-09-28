package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Term dates list + add/edit, with overlap warnings (WP-U3). */
@Composable
fun TermDatesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Term dates", onBack = onBack, modifier = modifier)
}
