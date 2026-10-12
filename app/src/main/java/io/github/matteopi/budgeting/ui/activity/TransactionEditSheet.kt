package io.github.matteopi.budgeting.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.DateField
import java.time.LocalDate

/** The Activity edit sheet: a single transaction's fields, or a transfer's shared fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditSheet(
    row: ActivityRow,
    accounts: List<Account>,
    categories: List<Category>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSaveSingle: (amount: Money, categoryId: Long?, accountId: Long, date: LocalDate, label: String, note: String) -> Unit,
    onSaveTransfer: (amount: Money, date: LocalDate, label: String) -> Unit,
    onDelete: () -> Unit,
    onViewReceipt: (receiptId: Long) -> Unit = {},
) {
    // Open fully: a form half off the screen hides its later fields and its Save button.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        when (row) {
            is ActivityRow.Single -> SingleTransactionEditor(
                row = row,
                accounts = accounts,
                categories = categories,
                today = today,
                onSave = onSaveSingle,
                onDelete = onDelete,
                onViewReceipt = onViewReceipt,
            )
            is ActivityRow.Transfer -> TransferEditor(
                row = row,
                today = today,
                onSave = onSaveTransfer,
                onDelete = onDelete,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleTransactionEditor(
    row: ActivityRow.Single,
    accounts: List<Account>,
    categories: List<Category>,
    today: LocalDate,
    onSave: (amount: Money, categoryId: Long?, accountId: Long, date: LocalDate, label: String, note: String) -> Unit,
    onDelete: () -> Unit,
    onViewReceipt: (receiptId: Long) -> Unit,
) {
    val tx = row.tx
    var isOut by remember(tx.id) { mutableStateOf(!tx.amount.isPositive) }
    var amountText by remember(tx.id) { mutableStateOf(tx.amount.abs().toInputText()) }
    var categoryId by remember(tx.id) { mutableStateOf(tx.categoryId) }
    var accountId by remember(tx.id) { mutableStateOf(tx.accountId) }
    var date by remember(tx.id) { mutableStateOf(tx.date) }
    var label by remember(tx.id) { mutableStateOf(tx.label) }
    var note by remember(tx.id) { mutableStateOf(tx.note) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val wantedFlow = if (isOut) CategoryFlow.OUT else CategoryFlow.IN
    val eligibleCategories = categories.filter { !it.archived && (it.flow == wantedFlow || it.flow == CategoryFlow.BOTH) }
    val parsedMagnitude = Money.parse(amountText)?.abs()
    val saveEnabled = parsedMagnitude != null && parsedMagnitude.isPositive && !date.isAfter(today)

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Edit transaction", style = MaterialTheme.typography.titleLarge)
        VerticalSpace()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = isOut, onClick = { isOut = true; categoryId = null }, label = { Text("Out") })
            FilterChip(selected = !isOut, onClick = { isOut = false; categoryId = null }, label = { Text("In") })
        }
        VerticalSpace()

        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("Amount") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = parsedMagnitude == null,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpace()

        CategoryPicker(
            categories = eligibleCategories,
            selectedCategoryId = categoryId,
            onSelect = { categoryId = it },
        )
        VerticalSpace()

        AccountPicker(
            accounts = accounts,
            selectedAccountId = accountId,
            onSelect = { accountId = it },
        )
        VerticalSpace()

        DateField(label = "Date", date = date, onDateChange = { date = it }, maxDate = today)
        VerticalSpace()

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpace()

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpace()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = saveEnabled,
                onClick = {
                    val magnitude = parsedMagnitude ?: return@Button
                    onSave(if (isOut) -magnitude else magnitude, categoryId, accountId, date, label, note)
                },
            ) { Text("Save") }
            OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("Delete") }
            // Beside Save and Delete rather than under them: the sheet is already close to full height.
            tx.receiptId?.let { receiptId ->
                OutlinedButton(onClick = { onViewReceipt(receiptId) }) { Text("View receipt") }
            }
        }
    }

    if (showDeleteConfirm) {
        val message = when (tx.kind) {
            TxKind.OPENING_BALANCE -> "This is the account's opening balance. Deleting it will change its balance history."
            TxKind.RECONCILIATION -> "This is a reconciliation record. Deleting it will change your balance history."
            else -> "This can't be undone."
        }
        ConfirmDialog(
            title = "Delete transaction?",
            text = message,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = "Delete",
        )
    }
}

@Composable
private fun TransferEditor(
    row: ActivityRow.Transfer,
    today: LocalDate,
    onSave: (amount: Money, date: LocalDate, label: String) -> Unit,
    onDelete: () -> Unit,
) {
    var amountText by remember(row.from.id) { mutableStateOf(row.to.amount.abs().toInputText()) }
    var date by remember(row.from.id) { mutableStateOf(row.from.date) }
    var label by remember(row.from.id) { mutableStateOf(row.from.label) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val parsedMagnitude = Money.parse(amountText)?.abs()
    val saveEnabled = parsedMagnitude != null && parsedMagnitude.isPositive && !date.isAfter(today)

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Edit transfer", style = MaterialTheme.typography.titleLarge)
        VerticalSpace()

        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("Amount") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = parsedMagnitude == null,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpace()

        DateField(label = "Date", date = date, onDateChange = { date = it }, maxDate = today)
        VerticalSpace()

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpace()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = saveEnabled,
                onClick = {
                    val magnitude = parsedMagnitude ?: return@Button
                    onSave(magnitude, date, label)
                },
            ) { Text("Save") }
            OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("Delete") }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete transfer?",
            text = "Both sides of this transfer will be removed. This can't be undone.",
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = "Delete",
        )
    }
}

@Composable
private fun VerticalSpace() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))
}
