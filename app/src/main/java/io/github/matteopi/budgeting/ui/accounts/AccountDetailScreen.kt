package io.github.matteopi.budgeting.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Balances, Update balance, Transfer and recent activity for one account (WP-U2). */
@Composable
fun AccountDetailScreen(
    accountId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Account", onBack = onBack, modifier = modifier)
}
