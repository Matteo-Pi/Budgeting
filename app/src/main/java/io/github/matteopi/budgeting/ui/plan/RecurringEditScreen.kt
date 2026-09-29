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
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.data.repo.RuleStart
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.DateField

/** Add/edit a recurring rule: label, direction, amount, account, frequency, posting mode (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringEditScreen(
    ruleId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecurringEditViewModel = viewModel(factory = RecurringEditViewModel.factory(ruleId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (ruleId == null) "Add recurring" else "Edit recurring") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ruleId != null) {
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
                    label = "Account",
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
                Text("Frequency", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = listOf(Frequency.DAILY, Frequency.WEEKLY, Frequency.MONTHLY, Frequency.YEARLY),
                    selected = state.frequency,
                    label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    onSelect = viewModel::setFrequency,
                )
            }
            item {
                OutlinedTextField(
                    value = state.intervalText,
                    onValueChange = viewModel::setIntervalText,
                    label = { Text("Every N (e.g. 2 = fortnightly if weekly)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                DateField(label = "Start date", date = state.startDate, onDateChange = viewModel::setStartDate)
                if (state.startsInPast) {
                    Text(
                        text = RuleStart.PAST_START_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Has an end date", modifier = Modifier.weight(1f))
                    Switch(checked = state.hasEndDate, onCheckedChange = viewModel::setHasEndDate)
                }
            }
            val endDate = state.endDate
            if (state.hasEndDate && endDate != null) {
                item {
                    DateField(label = "End date", date = endDate, onDateChange = viewModel::setEndDate)
                }
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
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Active", modifier = Modifier.weight(1f))
                    Switch(checked = state.active, onCheckedChange = viewModel::setActive)
                }
            }
            if (state.previewDates.isNotEmpty()) {
                item {
                    Text(
                        text = "Next: " + state.previewDates.joinToString(", ") { formatShortDate(it) },
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
            title = "Delete this rule?",
            text = "\"${state.label}\" will stop happening. Past transactions it already created are kept.",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            confirmLabel = "Delete",
        )
    }
}
