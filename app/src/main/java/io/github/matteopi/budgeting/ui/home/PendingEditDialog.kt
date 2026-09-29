package io.github.matteopi.budgeting.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.domain.PendingItem
import io.github.matteopi.budgeting.ui.activity.toInputText
import io.github.matteopi.budgeting.ui.components.DateField
import java.time.LocalDate

/** Lets the user tweak the amount and date of a pending confirmation before it is posted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingEditDialog(
    item: PendingItem,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (Money, LocalDate) -> Unit,
) {
    val initialMagnitude = item.defaultAmount().abs()
    var amountText by remember(item) { mutableStateOf(initialMagnitude.toInputText()) }
    var date by remember(item) { mutableStateOf(item.defaultDate()) }

    val parsedMagnitude = Money.parse(amountText)?.abs()
    val amountValid = parsedMagnitude != null && parsedMagnitude.isPositive
    val isIncome = item.defaultAmount().isPositive

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm ${item.label()}") },
        text = {
            Column {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = !amountValid,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DateField(label = "Date", date = date, onDateChange = { date = it }, maxDate = today)
            }
        },
        confirmButton = {
            TextButton(
                enabled = amountValid,
                onClick = {
                    val magnitude = parsedMagnitude ?: return@TextButton
                    onConfirm(if (isIncome) magnitude else -magnitude, date)
                },
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
