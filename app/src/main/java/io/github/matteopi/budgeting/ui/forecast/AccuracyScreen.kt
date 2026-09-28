package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** How well past forecasts matched reality, full model vs the tier-1 baseline (WP-U4). */
@Composable
fun AccuracyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Accuracy", onBack = onBack, modifier = modifier)
}
