package io.github.matteopi.budgeting.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import java.time.LocalDate

/**
 * Hosts the transaction edit sheet for a screen: shows [TransactionEditSheet] while [row] is non-null, hands what
 * the user asked for to [controller] and closes the sheet through [onDismiss], and shows the "change similar
 * transactions?" question that follows a category change. Activity, an account's page and its history screen
 * all use it, so editing behaves the same everywhere.
 *
 * [perspectiveAccountId] is the account the screen is about (null for Activity); [onOpenReceipt] opens a receipt
 * from the sheet's "View receipt" button.
 */
@Composable
fun TransactionEditHost(
    row: ActivityRow?,
    onDismiss: () -> Unit,
    controller: TransactionEditController,
    accounts: List<Account>,
    categories: List<Category>,
    today: LocalDate,
    perspectiveAccountId: Long? = null,
    onOpenReceipt: (receiptId: Long) -> Unit = {},
) {
    if (row != null) {
        TransactionEditSheet(
            row = row,
            accounts = accounts,
            categories = categories,
            today = today,
            perspectiveAccountId = perspectiveAccountId,
            onDismiss = onDismiss,
            onEdit = { edit ->
                controller.submit(edit)
                onDismiss()
            },
            onViewReceipt = { receiptId ->
                onDismiss()
                onOpenReceipt(receiptId)
            },
        )
    }

    val prompt by controller.prompt.collectAsStateWithLifecycle()
    prompt?.let { open ->
        val categoryName = categories.firstOrNull { it.id == open.categoryId }?.name ?: "that category"
        SimilarTransactionsDialog(
            prompt = open,
            categoryName = categoryName,
            onAnswer = controller::answer,
            onDismiss = controller::dismissPrompt,
        )
    }
}

/** "Change the other 7 "TESCO STORES" transactions to Groceries too?" with the rule checkbox, ticked to start with. */
@Composable
private fun SimilarTransactionsDialog(
    prompt: SimilarPrompt,
    categoryName: String,
    onAnswer: (changeAll: Boolean, rememberRule: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var rememberRule by remember(prompt) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change similar transactions?") },
        text = {
            Column {
                Text(prompt.question(categoryName), style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .toggleable(value = rememberRule, role = Role.Checkbox, onValueChange = { rememberRule = it }),
                ) {
                    Checkbox(checked = rememberRule, onCheckedChange = null)
                    Text(prompt.rememberLabel(categoryName), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true, rememberRule) }) { Text("Change all") } },
        dismissButton = { TextButton(onClick = { onAnswer(false, rememberRule) }) { Text("Just this one") } },
    )
}
