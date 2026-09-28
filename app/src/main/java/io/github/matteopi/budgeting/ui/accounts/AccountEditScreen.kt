package io.github.matteopi.budgeting.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.ui.components.ConfirmDialog

/** Add/edit an account: name, type, opening balance, overdraft limit, pool membership (WP-U2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    accountId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountEditViewModel = viewModel(factory = remember(accountId) { AccountEditViewModel.factory(accountId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onSaved()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Add account" else "Edit account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                singleLine = true,
                isError = !state.nameValid,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer12()

            Box {
                OutlinedTextField(
                    value = state.type.label(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { typeMenuExpanded = true },
                )
                DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                    AccountType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.label()) },
                            onClick = {
                                viewModel.setType(type)
                                typeMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer12()

            if (state.isNew) {
                OutlinedTextField(
                    value = state.balanceText,
                    onValueChange = viewModel::setBalanceText,
                    label = { Text("Current balance") },
                    singleLine = true,
                    isError = !state.balanceValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer12()
            }

            OutlinedTextField(
                value = state.overdraftText,
                onValueChange = viewModel::setOverdraftText,
                label = { Text("Overdraft limit") },
                singleLine = true,
                isError = !state.overdraftValid,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer12()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Include in safe-to-spend", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.includeInPool, onCheckedChange = viewModel::setIncludeInPool)
            }
            Spacer12()

            Button(
                onClick = viewModel::save,
                enabled = state.saveEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            if (!state.isNew) {
                Spacer12()
                OutlinedButton(
                    onClick = viewModel::archive,
                    enabled = !state.archived,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.archived) "Already archived" else "Archive")
                }
                Spacer12()
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete this account?",
            text = "This deletes all its transactions and can't be undone.",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete()
            },
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = "Delete",
        )
    }
}

private fun AccountType.label(): String = when (this) {
    AccountType.CURRENT -> "Current account"
    AccountType.SAVINGS -> "Savings account"
    AccountType.CASH -> "Cash"
    AccountType.CREDIT_CARD -> "Credit card"
    AccountType.OTHER -> "Other"
}

@Composable
private fun Spacer12() {
    Spacer(modifier = Modifier.height(12.dp))
}
