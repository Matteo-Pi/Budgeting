package io.github.matteopi.budgeting.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.quickadd.moneyToEntryText

/** One-tap quick-add presets: add/edit, reorder, delete. See docs/PLAN.md §6.5 "Presets". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = (LocalContext.current.applicationContext as BudgetApp).container
    val viewModel: PresetsViewModel = viewModel(factory = PresetsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<Preset?>(null) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Presets") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add preset")
            }
        },
    ) { innerPadding ->
        if (state.presets.isEmpty()) {
            EmptyState(
                message = "No presets yet — add one for a spend you log often, like a meal deal.",
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
        } else {
            val ordered = state.presets.sortedBy { it.sortOrder }
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(ordered, key = { _, preset -> preset.id }) { index, preset ->
                    PresetRow(
                        preset = preset,
                        categoryName = state.categories.firstOrNull { it.id == preset.categoryId }?.name ?: "",
                        accountName = state.accounts.firstOrNull { it.id == preset.accountId }?.name,
                        canMoveUp = index > 0,
                        canMoveDown = index < ordered.lastIndex,
                        onMoveUp = { viewModel.moveUp(preset.id) },
                        onMoveDown = { viewModel.moveDown(preset.id) },
                        onEdit = { editingPreset = preset },
                        onDelete = { pendingDeleteId = preset.id },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        PresetEditDialog(
            preset = null,
            categories = state.categories,
            accounts = state.accounts,
            onDismiss = { showAddDialog = false },
            onSave = { preset ->
                viewModel.save(preset)
                showAddDialog = false
            },
        )
    }

    editingPreset?.let { preset ->
        PresetEditDialog(
            preset = preset,
            categories = state.categories,
            accounts = state.accounts,
            onDismiss = { editingPreset = null },
            onSave = { updated ->
                viewModel.save(updated)
                editingPreset = null
            },
        )
    }

    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = "Delete preset?",
            text = "This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

@Composable
private fun PresetRow(
    preset: Preset,
    categoryName: String,
    accountName: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.label, style = MaterialTheme.typography.titleMedium)
                val direction = if (preset.flow == CategoryFlow.OUT) "Out" else "In"
                Text(
                    text = "$direction · ${preset.amount.format()} · $categoryName · ${accountName ?: "Default account"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun PresetEditDialog(
    preset: Preset?,
    categories: List<Category>,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onSave: (Preset) -> Unit,
) {
    var label by remember { mutableStateOf(preset?.label ?: "") }
    var amountText by remember { mutableStateOf(preset?.amount?.let(::moneyToEntryText) ?: "") }
    var flow by remember { mutableStateOf(preset?.flow ?: CategoryFlow.OUT) }
    var categoryId by remember { mutableStateOf(preset?.categoryId) }
    var accountId by remember { mutableStateOf(preset?.accountId) }
    var showOnWidget by remember { mutableStateOf(preset?.showOnWidget ?: true) }

    val eligibleCategories = categories.filter { !it.isSystem && it.flow == flow }
    val amount = Money.parse(amountText)
    val canSave = amount != null && amount.isPositive && categoryId != null && label.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (preset == null) "Add preset" else "Edit preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = flow == CategoryFlow.OUT,
                        onClick = { flow = CategoryFlow.OUT; categoryId = null },
                        label = { Text("Out") },
                    )
                    FilterChip(
                        selected = flow == CategoryFlow.IN,
                        onClick = { flow = CategoryFlow.IN; categoryId = null },
                        label = { Text("In") },
                    )
                }
                CategoryPicker(
                    categories = eligibleCategories,
                    selectedCategoryId = categoryId,
                    onSelect = { categoryId = it },
                )
                FilterChip(
                    selected = accountId == null,
                    onClick = { accountId = null },
                    label = { Text("Use default account") },
                )
                AccountPicker(
                    accounts = accounts,
                    selectedAccountId = accountId,
                    onSelect = { accountId = it },
                    label = "Or a specific account",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showOnWidget, onCheckedChange = { showOnWidget = it })
                    Text("Show on widget")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val finalAmount = amount ?: return@TextButton
                    val finalCategoryId = categoryId ?: return@TextButton
                    onSave(
                        Preset(
                            id = preset?.id ?: 0,
                            label = label.trim(),
                            amount = finalAmount.abs(),
                            flow = flow,
                            categoryId = finalCategoryId,
                            accountId = accountId,
                            showOnWidget = showOnWidget,
                            sortOrder = preset?.sortOrder ?: 0,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
