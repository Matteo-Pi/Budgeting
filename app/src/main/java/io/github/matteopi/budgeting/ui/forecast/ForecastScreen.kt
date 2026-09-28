package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** The Forecast tab: horizon chips, P10-P90 chart, risk sentence and budget periods (WP-U4). */
@Composable
fun ForecastScreen(
    onOpenPredictions: () -> Unit,
    onOpenScenarios: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenAccuracy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Forecast", modifier = modifier)
}
