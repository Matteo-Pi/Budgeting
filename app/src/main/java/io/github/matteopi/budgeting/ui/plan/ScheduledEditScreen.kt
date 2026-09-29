package io.github.matteopi.budgeting.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.DateField

/** Add/edit a one-off scheduled item (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledEditScreen(
    itemId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScheduledEditViewModel = viewModel(factory = ScheduledEditViewModel.factory(itemId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (itemId == null) "Add one-off" else "Edit one-off") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (itemId != null) {
                        IconButton(onClick = viewModel::requestDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.label,
                    onValueChange = viewModel::setLabel,
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("Direction", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = listOf(Direction.OUT, Direction.IN, Direction.TRANSFER),
                    selected = state.direction,
                    label = { when (it) { Direction.OUT -> "Money out"; Direction.IN -> "Money in"; Direction.TRANSFER -> "Transfer" } },
                    onSelect = viewModel::setDirection,
                )
            }
            item {
                OutlinedTextField(
                    value = state.amountText,
                    onValueChange = viewModel::setAmountText,
                    label = { Text("Amount (£)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AccountPicker(
                    accounts = state.accounts,
                    selectedAccountId = state.accountId,
                    onSelect = viewModel::setAccountId,
                )
            }
            if (state.direction == Direction.TRANSFER) {
                item {
                    AccountPicker(
                        accounts = state.accounts.filter { it.id != state.accountId },
                        selectedAccountId = state.toAccountId,
                        onSelect = viewModel::setToAccountId,
                        label = "To account",
                    )
                }
            } else {
                item {
                    val categoryFlow = if (state.direction == Direction.OUT) CategoryFlow.OUT else CategoryFlow.IN
                    CategoryPicker(
                        categories = state.categories.filter { it.flow == categoryFlow && !it.archived },
                        selectedCategoryId = state.categoryId,
                        onSelect = viewModel::setCategoryId,
                    )
                }
            }
            item {
                DateField(label = "Date", date = state.date, onDateChange = viewModel::setDate)
            }
            item {
                Text("Posting", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = listOf(PostingMode.AUTO, PostingMode.CONFIRM),
                    selected = state.postingMode,
                    label = { if (it == PostingMode.AUTO) "Happens automatically" else "I confirm it" },
                    onSelect = viewModel::setPostingMode,
                )
            }
            if (state.direction == Direction.IN) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Starts a new budget period (major income)", modifier = Modifier.weight(1f))
                        Switch(checked = state.isMajorIncome, onCheckedChange = viewModel::setMajorIncome)
                    }
                }
            }
            if (state.posted || state.skipped) {
                item {
                    Text(
                        text = if (state.posted) "Already posted." else "Marked as skipped.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.errorMessage != null) {
                item {
                    Text(text = state.errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                    Text("Save")
                }
            }
        }
    }

    if (state.showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete this one-off date?",
            text = "\"${state.label}\" will be removed.",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            confirmLabel = "Delete",
        )
    }
}
