package io.github.matteopi.budgeting.ui.quickadd

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A chip that opens a date picker limited to [today] and earlier; shows "Today" when that's picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateChip(
    date: LocalDate,
    today: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    AssistChip(
        onClick = { showPicker = true },
        label = { Text(if (date == today) "Today" else date.toString()) },
        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
        modifier = modifier,
    )

    if (showPicker) {
        val initialMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val maxMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val selectableDates = remember(maxMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= maxMillis
            }
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis, selectableDates = selectableDates)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        onDateChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

/** A chip that opens a dropdown to pick one of [accounts]. */
@Composable
fun AccountChip(
    accounts: List<Account>,
    selectedAccountId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.id == selectedAccountId }

    Box(modifier = modifier) {
        AssistChip(onClick = { expanded = true }, label = { Text(selected?.name ?: "Account") })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = { onSelect(account.id); expanded = false },
                )
            }
        }
    }
}

/** A "More" chip that opens a dropdown listing the categories that don't fit in the ranked row. */
@Composable
fun MoreCategoriesChip(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedCategoryId }

    Box(modifier = modifier) {
        AssistChip(onClick = { expanded = true }, label = { Text(selected?.name ?: "More") })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = { onSelect(category.id); expanded = false },
                )
            }
        }
    }
}

/** Starts as an "Add label" chip; once opened (by tapping it, or because a label is already set) it
 * stays open as a text field for the rest of this composition. */
@Composable
fun LabelField(
    label: String,
    onLabelChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var manuallyExpanded by remember { mutableStateOf(false) }
    val expanded = manuallyExpanded || label.isNotEmpty()

    if (expanded) {
        LaunchedEffect(Unit) { manuallyExpanded = true }
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text("Label") },
            singleLine = true,
            modifier = modifier,
        )
    } else {
        AssistChip(
            onClick = { manuallyExpanded = true },
            label = { Text("Add label") },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            modifier = modifier,
        )
    }
}
