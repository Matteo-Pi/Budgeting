package io.github.matteopi.budgeting.ui.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.ui.activity.toInputText

/**
 * "Update balance": the user types what their bank app shows (always the raw balance — banks
 * don't know about the zero-point setting) and previews the gap that will be recorded as
 * Untracked (docs/PLAN.md §6.5).
 */
@Composable
fun UpdateBalanceDialog(
    account: Account,
    expectedRaw: Money,
    zeroPoint: ZeroPoint,
    onDismiss: () -> Unit,
    onConfirm: (actualRaw: Money) -> Unit,
) {
    var text by remember { mutableStateOf(expectedRaw.toInputText()) }
    val actual = Money.parse(text)
    val difference = actual?.minus(expectedRaw)
    val expectedDisplay = Balances.toDisplay(expectedRaw, account.overdraftLimit, zeroPoint)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update balance") },
        text = {
            Column {
                Text("Enter the balance your bank app shows for ${account.name}.")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Balance") },
                    singleLine = true,
                    isError = actual == null,
                )
                Spacer(modifier = Modifier.height(12.dp))
                when {
                    actual == null -> Unit
                    difference == null || difference.isZero ->
                        Text("That matches what the app expected — nothing to record.")
                    else ->
                        Text(
                            "The app thought ${expectedDisplay.format()}. The ${difference.abs().format()} " +
                                "difference will be recorded as Untracked.",
                        )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = actual != null,
                onClick = { actual?.let(onConfirm) },
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
