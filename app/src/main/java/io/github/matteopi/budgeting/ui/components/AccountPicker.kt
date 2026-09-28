package io.github.matteopi.budgeting.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.core.model.Account

/** A tap-to-choose field for one of the user's accounts. */
@Composable
fun AccountPicker(
    accounts: List<Account>,
    selectedAccountId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Account",
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.id == selectedAccountId }

    Box(modifier = modifier) {
        PickerField(
            value = selected?.name ?: "",
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
        }
    }
}
