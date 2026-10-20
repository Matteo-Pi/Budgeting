package io.github.matteopi.budgeting.ui.planner.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.todo.TaskRules
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.planner.components.ColourDot
import io.github.matteopi.budgeting.ui.planner.components.DurationField

/** The colours a module can take: distinct in light and dark themes. */
val MODULE_COLOURS: List<Long> = listOf(
    0xFF3F7CAC, 0xFFD1495B, 0xFF2E8B57, 0xFFE59500, 0xFF7B4FA6, 0xFF00897B, 0xFF8D6E63, 0xFF5C6BC0,
)

/** The user's modules (docs/PLAN.md §12.3): code, name, colour, weekly study target and weight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlannerSettingsViewModel = viewModel(factory = PlannerSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Module?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Modules") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add module") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            if (!state.loading && state.modules.isEmpty()) {
                item(key = "empty") { EmptyState("No modules yet. Add one, such as \"EC101 Economics\", to group your tasks.") }
            }
            items(state.modules, key = { it.id }) { module ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { editing = module }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ColourDot(module.colorArgb ?: MODULE_COLOURS.first(), size = 16)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(module.label, style = MaterialTheme.typography.bodyLarge)
                        val detail = listOfNotNull(
                            module.weeklyTargetMinutes?.let { "${PlannerText.duration(it)} a week" },
                            weightLabel(module.weight),
                            if (module.archived) "Archived" else null,
                        ).joinToString(" · ")
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        ModuleDialog(
            initial = editing,
            modules = state.modules,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { module, onDone -> viewModel.saveModule(module, onDone) },
            onDelete = { id -> viewModel.deleteModule(id) },
        )
    }
}

fun weightLabel(weight: Int): String = when (weight) {
    1 -> "Light"
    3 -> "Heavy"
    else -> "Normal weight"
}

@Composable
private fun ModuleDialog(
    initial: Module?,
    modules: List<Module>,
    onDismiss: () -> Unit,
    onSave: (Module, (Boolean) -> Unit) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var code by remember { mutableStateOf(initial?.code ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var colour by remember { mutableStateOf(initial?.colorArgb ?: MODULE_COLOURS[modules.size % MODULE_COLOURS.size]) }
    var target by remember { mutableStateOf(initial?.weeklyTargetMinutes) }
    var weight by remember { mutableStateOf(initial?.weight ?: 2) }
    var archived by remember { mutableStateOf(initial?.archived ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var taken by remember { mutableStateOf(false) }
    val codeFree = TaskRules.moduleCodeFree(code, modules, exceptId = initial?.id ?: 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add module" else "Edit module") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        taken = false
                    },
                    label = { Text("Code") },
                    placeholder = { Text("EC101") },
                    isError = !codeFree || taken,
                    supportingText = if (!codeFree || taken) ({ Text("Another module has this code.") }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODULE_COLOURS.forEach { argb ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb.toInt()))
                                .border(if (argb == colour) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable(role = Role.RadioButton) { colour = argb },
                        )
                    }
                }
                DurationField(label = "Study target a week (optional)", minutes = target, onMinutesChange = { target = it })
                Text("How much it counts", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3).forEach { w ->
                        FilterChip(selected = weight == w, onClick = { weight = w }, label = { Text(weightLabel(w).removeSuffix(" weight")) })
                    }
                }
                if (initial != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Archived (hidden from pickers)", modifier = Modifier.weight(1f))
                        Switch(checked = archived, onCheckedChange = { archived = it })
                    }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete module", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.isNotBlank() && codeFree,
                onClick = {
                    val module = (initial ?: Module(code = code)).copy(
                        code = code.trim(),
                        name = name.trim(),
                        colorArgb = colour,
                        weeklyTargetMinutes = target,
                        weight = weight,
                        archived = archived,
                    )
                    onSave(module) { saved -> if (saved) onDismiss() else taken = true }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete && initial != null) {
        ConfirmDialog(
            title = "Delete ${initial.code}?",
            text = "Its tasks stay, with no module.",
            onConfirm = {
                onDelete(initial.id)
                confirmDelete = false
                onDismiss()
            },
            onDismiss = { confirmDelete = false },
            confirmLabel = "Delete",
        )
    }
}
