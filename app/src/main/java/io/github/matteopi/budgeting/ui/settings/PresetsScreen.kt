package io.github.matteopi.budgeting.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** One-tap quick-add presets: add/edit, reorder, delete (WP-U1). */
@Composable
fun PresetsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Presets", onBack = onBack, modifier = modifier)
}
