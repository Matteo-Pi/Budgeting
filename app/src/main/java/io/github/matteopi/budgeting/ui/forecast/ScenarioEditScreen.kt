package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Edit a what-if scenario's items and compare it against the baseline forecast (WP-U4). */
@Composable
fun ScenarioEditScreen(
    scenarioId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = if (scenarioId == null) "New scenario" else "Edit scenario", onBack = onBack, modifier = modifier)
}
