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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.ui.planner.components.ColourDot

/**
 * The day record's categories (docs/PLAN.md §12.8): rename, recolour, add and archive. The defaults keep their
 * meaning when renamed; a category with chunks is archived rather than deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlannerSettingsViewModel = viewModel(factory = PlannerSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TimeCategory?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Day record categories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = TimeCategory(name = "", colorArgb = MODULE_COLOURS.first()) },
                // Material clears the extended button's text from accessibility, so the icon carries its name.
                icon = { Icon(Icons.Default.Add, contentDescription = "Add category") },
                text = { Text("Add category") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            items(state.categories, key = { it.id }) { c ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { editing = c }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColourDot(c.colorArgb, size = 14)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.name, color = if (c.archived) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                        val notes = listOfNotNull(if (c.ratesFocus) "Rates focus" else null, if (c.archived) "Archived" else null)
                        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    editing?.let { category ->
        CategoryDialog(
            category = category,
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveCategory(it)
                editing = null
            },
        )
    }
}

@Composable
private fun CategoryDialog(category: TimeCategory, onDismiss: () -> Unit, onSave: (TimeCategory) -> Unit) {
    var name by remember { mutableStateOf(category.name) }
    var colour by remember { mutableStateOf(category.colorArgb) }
    var focus by remember { mutableStateOf(category.ratesFocus) }
    var archived by remember { mutableStateOf(category.archived) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (category.id == 0L) "Add category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (MODULE_COLOURS + listOf(0xFFC2185B, 0xFF455A64, 0xFF9E9E9E)).forEach { argb ->
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rates focus (1 to 5)", modifier = Modifier.weight(1f))
                    Switch(checked = focus, onCheckedChange = { focus = it })
                }
                if (category.id != 0L) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Archived (hidden when choosing)", modifier = Modifier.weight(1f))
                        Switch(checked = archived, onCheckedChange = { archived = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(category.copy(name = name.trim(), colorArgb = colour, ratesFocus = focus, archived = archived)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
