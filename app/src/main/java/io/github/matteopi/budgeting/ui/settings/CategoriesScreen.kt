package io.github.matteopi.budgeting.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Add/edit/archive categories (WP-U5). */
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Categories", onBack = onBack, modifier = modifier)
}
