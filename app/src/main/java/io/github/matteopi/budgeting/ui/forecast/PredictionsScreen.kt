package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Per-category, per-regime spending estimates: automatic vs the user's own (WP-U3). */
@Composable
fun PredictionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Predictions", onBack = onBack, modifier = modifier)
}
