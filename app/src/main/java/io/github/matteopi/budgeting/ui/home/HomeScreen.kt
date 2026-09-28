package io.github.matteopi.budgeting.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** The Home tab: safe-to-spend hero, cards, accounts, mini forecast and recent activity (WP-U2). */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onOpenAccountEdit: (Long?) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenForecast: () -> Unit,
    onOpenPredictions: () -> Unit,
    onAddTransaction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Home", modifier = modifier)
}
