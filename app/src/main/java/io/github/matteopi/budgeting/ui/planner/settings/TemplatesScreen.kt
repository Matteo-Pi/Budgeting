package io.github.matteopi.budgeting.ui.planner.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.TemplateStep
import io.github.matteopi.budgeting.ui.components.ConfirmDialog

/**
 * The breakdown templates (docs/PLAN.md §12.3): how *Add steps from a template* splits a deadline. Each step has a
 * share of the work; the shares need not add up to 100, they are taken relative to their total.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlannerSettingsViewModel = viewModel(factory = PlannerSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Breakdown templates") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { confirmReset = true }) { Icon(Icons.Default.Refresh, contentDescription = "Reset to the defaults") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingIndex = state.templates.size },
                // Material clears the extended button's text from accessibility, so the icon carries its name.
                icon = { Icon(Icons.Default.Add, contentDescription = "Add template") },
                text = { Text("Add template") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            itemsIndexed(state.templates, key = { index, t -> "$index-${t.name}" }) { index, template ->
                Column(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { editingIndex = index }.padding(vertical = 10.dp),
                ) {
                    Text(template.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        template.steps.joinToString(" → ") { "${it.title} ${it.percent}%" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    editingIndex?.let { index ->
        TemplateDialog(
            initial = state.templates.getOrNull(index),
            onDismiss = { editingIndex = null },
            onSave = { template ->
                viewModel.saveTemplate(index, template)
                editingIndex = null
            },
            onDelete = if (index < state.templates.size && state.templates.size > 1) {
                {
                    viewModel.deleteTemplate(index)
                    editingIndex = null
                }
            } else {
                null
            },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset the templates?",
            text = "Your changes to the templates are replaced by the four defaults.",
            onConfirm = {
                viewModel.resetTemplates()
                confirmReset = false
            },
            onDismiss = { confirmReset = false },
            confirmLabel = "Reset",
        )
    }
}

@Composable
private fun TemplateDialog(
    initial: BreakdownTemplate?,
    onDismiss: () -> Unit,
    onSave: (BreakdownTemplate) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    val steps = remember { mutableStateListOf<Pair<String, String>>().apply { addAll((initial?.steps ?: emptyList()).map { it.title to it.percent.toString() }) } }
    val parsed = steps.map { (title, percent) -> TemplateStep(title.trim(), percent.toIntOrNull() ?: 0) }
    val valid = name.isNotBlank() && parsed.any { it.title.isNotEmpty() && it.percent > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add template" else "Edit template") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                steps.forEachIndexed { index, (title, percent) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { steps[index] = it to percent },
                            label = { Text("Step ${index + 1}") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = percent,
                            onValueChange = { value -> steps[index] = title to value.filter { it.isDigit() }.take(3) },
                            label = { Text("%") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(72.dp),
                        )
                        IconButton(onClick = { steps.removeAt(index) }) { Icon(Icons.Default.Close, contentDescription = "Remove step ${index + 1}") }
                    }
                }
                TextButton(onClick = { steps += "" to "10" }) { Text("Add step") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete template", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(BreakdownTemplate(name.trim(), parsed.filter { it.title.isNotEmpty() && it.percent > 0 }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
