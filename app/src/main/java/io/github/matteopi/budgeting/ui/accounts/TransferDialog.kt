package io.github.matteopi.budgeting.ui.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.DateField
import java.time.LocalDate

/** Transfer money out of this account into another of the user's accounts (docs/PLAN.md §6.5). */
@Composable
fun TransferDialog(
    fromAccountName: String,
    otherAccounts: List<Account>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (toAccountId: Long, amount: Money, date: LocalDate, label: String) -> Unit,
) {
    var toAccountId by remember { mutableStateOf(otherAccounts.firstOrNull()?.id) }
    var amountText by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today) }
    var label by remember { mutableStateOf("") }

    val parsedMagnitude = Money.parse(amountText)?.abs()
    val valid = toAccountId != null && parsedMagnitude != null && parsedMagnitude.isPositive && !date.isAfter(today)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer from $fromAccountName") },
        text = {
            Column {
                if (otherAccounts.isEmpty()) {
                    Text("Add another account first to transfer money.")
                } else {
                    AccountPicker(
                        accounts = otherAccounts,
                        selectedAccountId = toAccountId,
                        onSelect = { toAccountId = it },
                        label = "To account",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = parsedMagnitude == null,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DateField(label = "Date", date = date, onDateChange = { date = it }, maxDate = today)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val id = toAccountId ?: return@TextButton
                    val amount = parsedMagnitude ?: return@TextButton
                    onConfirm(id, amount, date, label)
                },
            ) { Text("Transfer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
