package io.github.matteopi.budgeting.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.repo.TransferEdits
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.components.PickerField
import java.time.LocalDate

/** The text of the choice that means "the other end of this move isn't one of my accounts". */
const val NOT_IN_THE_APP = "Not in the app"

/**
 * The edit sheet shared by Activity, an account's page and an account's history: a single transaction's fields,
 * a starting balance, or both sides of a transfer (docs/PLAN.md §6.5, "Editing"). It never touches the database:
 * it reports what the user asked for as a [TransactionEdit] through [onEdit] (the host then dismisses it).
 *
 * [perspectiveAccountId] is the account the sheet was opened from (an account's page or history), or null from
 * Activity; "Not a transfer" keeps the transfer on that account, and asks when it is null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditSheet(
    row: ActivityRow,
    accounts: List<Account>,
    categories: List<Category>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onEdit: (TransactionEdit) -> Unit,
    perspectiveAccountId: Long? = null,
    onViewReceipt: (receiptId: Long) -> Unit = {},
) {
    // Open fully: a form half off the screen hides its later fields and its Save button.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        when (row) {
            is ActivityRow.Transfer -> TransferEditor(
                sides = TransferEdits.Sides(outgoing = row.from, incoming = row.to),
                accounts = accounts,
                categories = categories,
                today = today,
                perspectiveAccountId = perspectiveAccountId,
                onEdit = onEdit,
            )
            is ActivityRow.Single -> when (EditRules.editorKind(row)) {
                EditRules.EditorKind.TRANSFER -> TransferEditor(
                    sides = TransferEdits.sides(listOf(row.tx)),
                    accounts = accounts,
                    categories = categories,
                    today = today,
                    perspectiveAccountId = perspectiveAccountId,
                    onEdit = onEdit,
                )
                EditRules.EditorKind.STARTING_BALANCE -> StartingBalanceEditor(
                    tx = row.tx,
                    today = today,
                    onEdit = onEdit,
                )
                EditRules.EditorKind.IMPORT_ENTRY -> ImportEntryNotice(onClose = onDismiss)
                EditRules.EditorKind.SINGLE -> SingleTransactionEditor(
                    tx = row.tx,
                    accounts = accounts,
                    categories = categories,
                    today = today,
                    onEdit = onEdit,
                    onViewReceipt = onViewReceipt,
                )
            }
        }
    }
}

@Composable
private fun SingleTransactionEditor(
    tx: Transaction,
    accounts: List<Account>,
    categories: List<Category>,
    today: LocalDate,
    onEdit: (TransactionEdit) -> Unit,
    onViewReceipt: (receiptId: Long) -> Unit,
) {
    var isOut by remember(tx.id) { mutableStateOf(!tx.amount.isPositive) }
    var amountText by remember(tx.id) { mutableStateOf(tx.amount.abs().toInputText()) }
    var categoryId by remember(tx.id) { mutableStateOf(tx.categoryId) }
    var accountId by remember(tx.id) { mutableStateOf(tx.accountId) }
    var date by remember(tx.id) { mutableStateOf(tx.date) }
    var label by remember(tx.id) { mutableStateOf(tx.label) }
    var note by remember(tx.id) { mutableStateOf(tx.note) }
    var moveBetween by remember(tx.id) { mutableStateOf(false) }
    var otherAccountId by remember(tx.id) { mutableStateOf<Long?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val canMove = EditRules.canMoveBetweenAccounts(tx)
    val eligibleCategories = EditRules.eligibleCategories(categories, out = isOut)
    val otherChoices = EditRules.otherAccountChoices(accounts, accountId)
    // The other account can't be the one the money is on (the Account field may have changed since it was chosen).
    val effectiveOther = otherAccountId?.takeIf { id -> otherChoices.any { it.id == id } }
    val parsedMagnitude = Money.parse(amountText)?.abs()
    val saveEnabled = parsedMagnitude != null && parsedMagnitude.isPositive && !date.isAfter(today)

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Edit transaction", style = MaterialTheme.typography.titleLarge)
        val topNote = when {
            EditRules.isReceiptPart(tx) -> EditRules.RECEIPT_PART_NOTE
            tx.kind == TxKind.RECONCILIATION -> EditRules.BALANCE_CHECK_NOTE
            else -> null
        }
        if (topNote != null) {
            Text(
                text = topNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        FormSpacer()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = isOut, onClick = { isOut = true; categoryId = null }, label = { Text("Out") })
            FilterChip(selected = !isOut, onClick = { isOut = false; categoryId = null }, label = { Text("In") })
        }
        FormSpacer()

        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("Amount") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = parsedMagnitude == null,
            modifier = Modifier.fillMaxWidth(),
        )
        FormSpacer()

        if (canMove) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Move between my accounts", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = moveBetween, onCheckedChange = { moveBetween = it })
            }
            FormSpacer()
        }

        if (moveBetween) {
            OptionalAccountPicker(
                accounts = otherChoices,
                selectedAccountId = effectiveOther,
                onSelect = { otherAccountId = it },
                label = "Other account",
            )
        } else {
            CategoryPicker(
                categories = eligibleCategories,
                selectedCategoryId = categoryId,
                onSelect = { categoryId = it },
            )
        }
        FormSpacer()

        AccountPicker(
            accounts = accounts,
            selectedAccountId = accountId,
            onSelect = { accountId = it },
        )
        FormSpacer()

        DateField(label = "Date", date = date, onDateChange = { date = it }, maxDate = today)
        FormSpacer()

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FormSpacer()

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
        FormSpacer()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = saveEnabled,
                onClick = {
                    val magnitude = parsedMagnitude ?: return@Button
                    val signed = if (isOut) -magnitude else magnitude
                    if (moveBetween && canMove) {
                        onEdit(TransactionEdit.ToTransfer(tx, signed, accountId, date, label, note, effectiveOther))
                    } else {
                        onEdit(TransactionEdit.Single(tx, signed, categoryId, accountId, date, label, note))
                    }
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
                onEdit(TransactionEdit.Delete(tx.id))
            },
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = "Delete",
        )
    }
}

/** An account's opening balance: a signed amount (negative if it started overdrawn) and a date, no category. */
@Composable
private fun StartingBalanceEditor(
    tx: Transaction,
    today: LocalDate,
    onEdit: (TransactionEdit) -> Unit,
) {
    var amountText by remember(tx.id) { mutableStateOf(tx.amount.toInputText()) }
    var date by remember(tx.id) { mutableStateOf(tx.date) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val parsed = Money.parse(amountText)
    val saveEnabled = parsed != null && !date.isAfter(today)

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Edit starting balance", style = MaterialTheme.typography.titleLarge)
        Text(
            text = EditRules.STARTING_BALANCE_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        FormSpacer()

        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("Starting balance") },
            supportingText = { Text("Use a minus sign if the account started overdrawn.") },
            singleLine = true,
            isError = parsed == null,
            modifier = Modifier.fillMaxWidth(),
        )
        FormSpacer()

        DateField(label = "Date", date = date, onDateChange = { date = it }, maxDate = today)
        FormSpacer()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = saveEnabled,
                onClick = {
                    val amount = parsed ?: return@Button
                    onEdit(TransactionEdit.Single(tx, amount, null, tx.accountId, date, tx.label, tx.note))
                },
            ) { Text("Save") }
            OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("Delete") }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete transaction?",
            text = "This is the account's opening balance. Deleting it will change its balance history.",
            onConfirm = {
                showDeleteConfirm = false
                onEdit(TransactionEdit.Delete(tx.id))
            },
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = "Delete",
        )
    }
}

/** An opening-balance entry a statement import made: explained, with nothing to change. */
@Composable
private fun ImportEntryNotice(onClose: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Starting balance", style = MaterialTheme.typography.titleLarge)
        FormSpacer()
        Text(EditRules.IMPORT_ENTRY_NOTE, style = MaterialTheme.typography.bodyMedium)
        FormSpacer()
        OutlinedButton(onClick = onClose) { Text("Close") }
    }
}

/**
 * A tap-to-choose field for an account that may also be "Not in the app" (null): the other end of a move
 * between accounts when it is not one of the user's accounts. [accounts] are the accounts to offer.
 */
@Composable
internal fun OptionalAccountPicker(
    accounts: List<Account>,
    selectedAccountId: Long?,
    onSelect: (Long?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = accounts.firstOrNull { it.id == selectedAccountId }?.name ?: NOT_IN_THE_APP

    Box(modifier = modifier) {
        PickerField(
            value = shown,
            label = label,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onSelect(account.id)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(NOT_IN_THE_APP) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
        }
    }
}

@Composable
internal fun FormSpacer() {
    Spacer(modifier = Modifier.height(10.dp))
}
