package io.github.matteopi.budgeting.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Add/edit an account: name, type, opening balance, overdraft limit, pool membership (WP-U2). */
@Composable
fun AccountEditScreen(
    accountId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = if (accountId == null) "Add account" else "Edit account", onBack = onBack, modifier = modifier)
}
