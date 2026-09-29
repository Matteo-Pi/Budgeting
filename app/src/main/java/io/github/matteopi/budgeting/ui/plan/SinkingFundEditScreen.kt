package io.github.matteopi.budgeting.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.components.PickerField

/** Add/edit a sinking fund: name, target, start/target dates, category (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SinkingFundEditScreen(
    fundId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SinkingFundEditViewModel = viewModel(factory = SinkingFundEditViewModel.factory(fundId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (fundId == null) "Add fund" else "Edit fund") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (fundId != null) {
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
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.targetText,
                    onValueChange = viewModel::setTargetText,
                    label = { Text("Target (£)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                DateField(label = "Start date", date = state.startDate, onDateChange = viewModel::setStartDate)
            }
            item {
                DateField(label = "Target date", date = state.targetDate, onDateChange = viewModel::setTargetDate)
            }
            item {
                OptionalCategoryPicker(
                    categories = outCategoriesFor(state.categories),
                    selectedCategoryId = state.categoryId,
                    onSelect = viewModel::setCategoryId,
                )
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
            if (fundId != null && state.status == SinkingFundStatus.ACTIVE) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::markDone, modifier = Modifier.weight(1f)) {
                            Text("Mark as done")
                        }
                        OutlinedButton(onClick = viewModel::cancelFund, modifier = Modifier.weight(1f)) {
                            Text("Cancel fund")
                        }
                    }
                }
            } else if (fundId != null) {
                item {
                    Text(
                        text = if (state.status == SinkingFundStatus.COMPLETED) "This fund is marked done." else "This fund is cancelled.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (state.showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete this fund?",
            text = "\"${state.name}\" will be removed.",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            confirmLabel = "Delete",
        )
    }
}

/** A [io.github.matteopi.budgeting.ui.components.CategoryPicker]-alike that also offers "None". */
@Composable
private fun OptionalCategoryPicker(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "None"

    Box(modifier = Modifier.fillMaxWidth()) {
        PickerField(
            value = selectedName,
            label = "Category (optional)",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = { onSelect(null); expanded = false },
            )
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = { onSelect(category.id); expanded = false },
                )
            }
        }
    }
}
