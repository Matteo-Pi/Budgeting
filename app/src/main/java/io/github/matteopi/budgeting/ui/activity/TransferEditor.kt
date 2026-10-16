package io.github.matteopi.budgeting.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.data.repo.TransferEdits
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.DateField
import java.time.LocalDate

/**
 * The form for a transfer: its amount, date and label, and which account the money leaves and arrives in. Either
 * side can be changed, or set to "Not in the app" (that side then has no leg); a transfer that has only one leg
 * is edited here too, and giving its missing side an account adds the leg. "Not a transfer" turns one side back
 * into an ordinary transaction.
 */
@Composable
internal fun TransferEditor(
    sides: TransferEdits.Sides,
    accounts: List<Account>,
    categories: List<Category>,
    today: LocalDate,
    perspectiveAccountId: Long?,
    onEdit: (TransactionEdit) -> Unit,
) {
    val legs = sides.legs
    val reference = sides.incoming ?: legs.first()
    val key = legs.map { it.id }
    var fromId by remember(key) { mutableStateOf(sides.outgoing?.accountId) }
    var toId by remember(key) { mutableStateOf(sides.incoming?.accountId) }
    var amountText by remember(key) { mutableStateOf(reference.amount.abs().toInputText()) }
    var date by remember(key) { mutableStateOf(reference.date) }
    var label by remember(key) { mutableStateOf(legs.first().label) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNotATransfer by remember { mutableStateOf(false) }

    val parsedMagnitude = Money.parse(amountText)?.abs()
    val sidesError = EditRules.transferSidesError(fromId, toId)
    val saveEnabled = parsedMagnitude != null && parsedMagnitude.isPositive && !date.isAfter(today) && sidesError == null

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Edit transfer", style = MaterialTheme.typography.titleLarge)
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

        OptionalAccountPicker(
            accounts = EditRules.transferAccountChoices(accounts, fromId),
            selectedAccountId = fromId,
            onSelect = { fromId = it },
            label = "From account",
        )
        FormSpacer()
        OptionalAccountPicker(
            accounts = EditRules.transferAccountChoices(accounts, toId),
            selectedAccountId = toId,
            onSelect = { toId = it },
            label = "To account",
        )
        if (sidesError != null) {
            Text(
                text = sidesError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else if (fromId == null || toId == null) {
            Text(
                text = "The other side is an account that isn't in the app. Pick an account to add it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = saveEnabled,
                onClick = {
                    val magnitude = parsedMagnitude ?: return@Button
                    onEdit(TransactionEdit.Transfer(reference.id, fromId, toId, magnitude, date, label))
                },
            ) { Text("Save") }
            OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("Delete") }
        }
        FormSpacer()
        OutlinedButton(onClick = { showNotATransfer = true }) { Text("Not a transfer") }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete transfer?",
            text = if (legs.size > 1) {
                "Both sides of this transfer will be removed. This can't be undone."
            } else {
                "This can't be undone."
            },
            onConfirm = {
                showDeleteConfirm = false
                onEdit(TransactionEdit.Delete(reference.id))
            },
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = "Delete",
        )
    }

    if (showNotATransfer) {
        NotATransferDialog(
            sides = sides,
            accounts = accounts,
            categories = categories,
            perspectiveAccountId = perspectiveAccountId,
            onConfirm = { keepId, categoryId ->
                showNotATransfer = false
                onEdit(TransactionEdit.ToNormal(keepId, categoryId))
            },
            onDismiss = { showNotATransfer = false },
        )
    }
}

/**
 * Turns one side of a transfer back into an ordinary transaction. The side to keep is the account the sheet was
 * opened from, or is asked for (from Activity); the user picks a category; the other side is deleted, which the
 * dialog says before it is confirmed.
 */
@Composable
private fun NotATransferDialog(
    sides: TransferEdits.Sides,
    accounts: List<Account>,
    categories: List<Category>,
    perspectiveAccountId: Long?,
    onConfirm: (keepId: Long, categoryId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val legs = sides.legs
    var keepId by remember { mutableStateOf(legs.firstOrNull { it.accountId == perspectiveAccountId }?.id ?: legs.singleOrNull()?.id) }
    var categoryId by remember { mutableStateOf<Long?>(null) }

    val keep = legs.firstOrNull { it.id == keepId }
    val dropped = if (keep != null) legs.firstOrNull { it.id != keep.id } else null
    val eligible = if (keep != null) EditRules.eligibleCategories(categories, out = keep.amount.isNegative) else emptyList()
    val nameOf = { id: Long -> accounts.firstOrNull { it.id == id }?.name ?: "another account" }
    // Opened from Activity the transfer isn't "on" either account, so the user says which side to keep.
    val needsChoice = legs.size > 1 && legs.none { it.accountId == perspectiveAccountId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make it a normal transaction") },
        text = {
            Column {
                if (needsChoice) {
                    AccountPicker(
                        accounts = legs.mapNotNull { leg -> accounts.firstOrNull { it.id == leg.accountId } },
                        selectedAccountId = keep?.accountId,
                        onSelect = { accountId ->
                            keepId = legs.firstOrNull { it.accountId == accountId }?.id
                            categoryId = null
                        },
                        label = "Keep it on",
                    )
                    FormSpacer()
                }
                CategoryPicker(
                    categories = eligible,
                    selectedCategoryId = categoryId,
                    onSelect = { categoryId = it },
                )
                FormSpacer()
                Text(
                    text = when {
                        dropped != null -> "The other side of this transfer, on ${nameOf(dropped.accountId)}, will be deleted."
                        legs.size > 1 -> "Choose the account to keep it on. The other side of this transfer will be deleted."
                        else -> "The other side was never in the app, so nothing else changes."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = keep != null && categoryId != null,
                onClick = {
                    val keepLeg = keep ?: return@TextButton
                    val chosen = categoryId ?: return@TextButton
                    onConfirm(keepLeg.id, chosen)
                },
            ) { Text("Make normal") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
