package io.github.matteopi.budgeting.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.displayName
import io.github.matteopi.budgeting.data.settings.poolDiffersFromAll

/**
 * A compact row of chips above a chart: "All accounts", "Safe-to-spend accounts" (only when some
 * account is left out of safe-to-spend, otherwise it would be the same as "All accounts"), each
 * account by name, and (when [allowEach]) "Each account". [accounts] should be the non-archived ones and [selected] an
 * already-resolved view (see [io.github.matteopi.budgeting.data.settings.resolveForChart]).
 * With a single account there is nothing to choose, so nothing is shown.
 */
@Composable
fun AccountViewSelector(
    accounts: List<Account>,
    selected: AccountView,
    onSelect: (AccountView) -> Unit,
    modifier: Modifier = Modifier,
    allowEach: Boolean = true,
) {
    if (accounts.size < 2) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == AccountView.All,
            onClick = { onSelect(AccountView.All) },
            label = { Text(AccountView.All.displayName(accounts)) },
        )
        if (poolDiffersFromAll(accounts)) {
            FilterChip(
                selected = selected == AccountView.Pool,
                onClick = { onSelect(AccountView.Pool) },
                label = { Text(AccountView.Pool.displayName(accounts)) },
            )
        }
        accounts.forEach { account ->
            FilterChip(
                selected = selected == AccountView.Single(account.id),
                onClick = { onSelect(AccountView.Single(account.id)) },
                label = { Text(account.name) },
            )
        }
        if (allowEach) {
            FilterChip(
                selected = selected == AccountView.Each,
                onClick = { onSelect(AccountView.Each) },
                label = { Text(AccountView.Each.displayName(accounts)) },
            )
        }
    }
}

/**
 * A small drop-down chip ("All accounts ▾") that picks all accounts, the safe-to-spend accounts
 * (offered only when some account is left out of safe-to-spend) or one account, for a single
 * balance number. [selected] should be a resolved balance view (never
 * [AccountView.Each]).
 */
@Composable
fun AccountViewChip(
    accounts: List<Account>,
    selected: AccountView,
    onSelect: (AccountView) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        AssistChip(
            onClick = { expanded = true },
            modifier = Modifier.testTag("account-view-chip"),
            label = { Text(selected.displayName(accounts)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(AccountView.All.displayName(accounts)) },
                onClick = {
                    onSelect(AccountView.All)
                    expanded = false
                },
            )
            if (poolDiffersFromAll(accounts)) {
                DropdownMenuItem(
                    text = { Text(AccountView.Pool.displayName(accounts)) },
                    onClick = {
                        onSelect(AccountView.Pool)
                        expanded = false
                    },
                )
            }
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onSelect(AccountView.Single(account.id))
                        expanded = false
                    },
                )
            }
        }
    }
}
