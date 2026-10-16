package io.github.matteopi.budgeting.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.ui.activity.ActivityRow
import io.github.matteopi.budgeting.ui.activity.ActivityRowView
import io.github.matteopi.budgeting.ui.activity.TransactionEditHost
import io.github.matteopi.budgeting.ui.activity.toShortLabelWithYear
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.SectionCard

/** Balances, Update balance, Transfer and recent activity for one account (WP-U2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    accountId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenHistory: (Long) -> Unit = {},
    onOpenReceipt: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AccountDetailViewModel = viewModel(factory = remember(accountId) { AccountDetailViewModel.factory(accountId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showUpdateBalance by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<ActivityRow?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.account?.name ?: "Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(accountId) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit account")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // The whole card opens the history. The tag sits on the card, not on the figure: a clickable
                // card merges its children, and a child's test tag is not visible in the merged tree.
                SectionCard(
                    modifier = Modifier
                        .testTag("account-balance")
                        .clickable(onClickLabel = "See full history") { onOpenHistory(accountId) },
                ) {
                    MoneyText(
                        amount = state.displayBalance,
                        style = MaterialTheme.typography.headlineLarge,
                        isBalance = true,
                    )
                    if (state.zeroPoint == ZeroPoint.OVERDRAFT_LIMIT) {
                        Text(
                            text = "above your overdraft limit",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Raw balance: ${state.rawBalance.format()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val overdraft = state.account?.overdraftLimit
                    if (overdraft != null && overdraft.isPositive) {
                        Text(
                            text = "Overdraft limit: ${overdraft.format()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { showUpdateBalance = true }) { Text("Update balance") }
                    OutlinedButton(onClick = { showTransfer = true }) { Text("Transfer") }
                }
            }

            item {
                SectionCard(title = "Recent activity") {
                    if (state.recentRows.isEmpty()) {
                        EmptyState(message = "No transactions yet for this account.")
                    } else {
                        state.recentRows.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider()
                            ActivityRowView(
                                row = row,
                                categoriesById = state.categoriesById,
                                accountsById = state.accountsById,
                                onClick = { editingRow = row },
                                perspectiveAccountId = accountId,
                            )
                        }
                        OutlinedButton(
                            onClick = { onOpenHistory(accountId) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("See full history") }
                    }
                }
            }

            item {
                SectionCard(title = "Reconciliation history") {
                    if (state.reconciliations.isEmpty()) {
                        Text(
                            text = "No balance checks yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.reconciliations.forEachIndexed { index, reconciliation ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(reconciliation.date.toShortLabelWithYear(), style = MaterialTheme.typography.bodyMedium)
                                val diff = reconciliation.actual - reconciliation.expected
                                Text(
                                    text = if (diff.isZero) "No change" else "${diff.format(showPlus = true)} adjustment",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    TransactionEditHost(
        row = editingRow,
        onDismiss = { editingRow = null },
        controller = viewModel.editing,
        accounts = state.accounts,
        categories = state.categories,
        today = state.today,
        perspectiveAccountId = accountId,
        onOpenReceipt = onOpenReceipt,
    )

    val account = state.account
    if (showUpdateBalance && account != null) {
        UpdateBalanceDialog(
            account = account,
            expectedRaw = state.rawBalance,
            zeroPoint = state.zeroPoint,
            onDismiss = { showUpdateBalance = false },
            onConfirm = { actual ->
                viewModel.updateBalance(actual)
                showUpdateBalance = false
            },
        )
    }

    if (showTransfer && account != null) {
        TransferDialog(
            fromAccountName = account.name,
            otherAccounts = state.otherAccounts,
            today = state.today,
            onDismiss = { showTransfer = false },
            onConfirm = { toAccountId, amount, date, label ->
                viewModel.transfer(toAccountId, amount, date, label)
                showTransfer = false
            },
        )
    }
}
