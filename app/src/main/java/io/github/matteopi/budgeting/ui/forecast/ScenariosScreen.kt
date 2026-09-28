package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** What-if scenario list (WP-U4). */
@Composable
fun ScenariosScreen(
    onBack: () -> Unit,
    onOpenScenario: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "What-if", onBack = onBack, modifier = modifier)
}
