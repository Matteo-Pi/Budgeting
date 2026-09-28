package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.PickerField

/**
 * Step 2: the accounts added so far, quick-add suggestion chips, and an add-account dialog
 * (docs/PLAN.md §6.5). Next is allowed with zero accounts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    var dialogState by remember { mutableStateOf<AccountDialogState?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your accounts", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add the accounts you spend from — you can add more, or change these, later.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            ACCOUNT_SUGGESTIONS.forEach { suggestion ->
                AssistChip(
                    onClick = { dialogState = AccountDialogState(name = suggestion.name, type = suggestion.type) },
                    label = { Text(suggestion.name) },
                )
            }
        }
        if (state.accounts.isEmpty()) {
            EmptyState(message = "No accounts yet — that's OK, you can add one now or skip and add it later from Home.")
        } else {
            state.accounts.forEach { account ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(account.name, style = MaterialTheme.typography.titleMedium)
                        Text(account.type.accountTypeLabel(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        OutlinedButton(onClick = { dialogState = AccountDialogState() }) {
            Text("Add account")
        }
    }

    val dialog = dialogState
    if (dialog != null) {
        AddAccountDialog(
            initial = dialog,
            onDismiss = { dialogState = null },
            onSave = { name, type, balance, overdraft, includeInPool ->
                viewModel.addAccount(name, type, balance, overdraft, includeInPool)
                dialogState = null
            },
        )
    }
}

private fun AccountType.accountTypeLabel(): String = when (this) {
    AccountType.CURRENT -> "Current account"
    AccountType.SAVINGS -> "Savings account"
    AccountType.CASH -> "Cash"
    AccountType.CREDIT_CARD -> "Credit card"
    AccountType.OTHER -> "Other"
}

private data class AccountDialogState(
    val name: String = "",
    val type: AccountType = AccountType.CURRENT,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountDialog(
    initial: AccountDialogState,
    onDismiss: () -> Unit,
    onSave: (name: String, type: AccountType, balance: Money, overdraft: Money, includeInPool: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var balanceText by remember { mutableStateOf("0.00") }
    var overdraftText by remember { mutableStateOf("0.00") }
    var includeInPool by remember { mutableStateOf(true) }

    val balance = Money.parse(balanceText)
    val overdraft = Money.parse(overdraftText)?.takeIf { !it.isNegative }
    val canSave = name.isNotBlank() && balance != null && overdraft != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    PickerField(
                        value = type.accountTypeLabel(),
                        label = "Type",
                        onClick = { typeMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                        AccountType.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.accountTypeLabel()) },
                                onClick = { type = option; typeMenuExpanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("Current balance (as shown in your bank app)") },
                    isError = balance == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Can be negative if you're overdrawn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = overdraftText,
                    onValueChange = { overdraftText = it },
                    label = { Text("Overdraft limit") },
                    isError = overdraft == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Include in safe-to-spend", modifier = Modifier.weight(1f))
                    Switch(checked = includeInPool, onCheckedChange = { includeInPool = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, type, balance!!, overdraft!!, includeInPool) },
                enabled = canSave,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
