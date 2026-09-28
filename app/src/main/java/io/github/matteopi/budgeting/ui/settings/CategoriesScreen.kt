package io.github.matteopi.budgeting.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.ui.components.EmptyState

/** A small, fixed set of colours categories can be given (docs/PLAN.md §6.5). */
private val CATEGORY_COLOR_PALETTE: List<Long> = listOf(
    0xFF2E7D32, 0xFFEF6C00, 0xFF6A1B9A, 0xFF1565C0, 0xFFAD1457,
    0xFF00838F, 0xFFC62828, 0xFF546E7A, 0xFF4E342E, 0xFF283593,
    0xFF1B5E20, 0xFF33691E, 0xFF827717, 0xFF004D40, 0xFF0277BD,
    0xFF558B2F, 0xFF9E9E9E,
)

/** Add/edit/archive categories, grouped Out / In / System; the system Untracked category can
 * only have its colour changed (WP-U5, docs/PLAN.md §6.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = viewModel(factory = CategoriesViewModel.Factory),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Category?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add category")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (categories.isEmpty()) {
            EmptyState(message = "No categories yet", modifier = Modifier.padding(innerPadding))
        } else {
            val outCategories = categories.filter { it.flow == CategoryFlow.OUT && !it.isSystem }
                .sortedWith(compareBy({ it.archived }, { it.sortOrder }, { it.name }))
            val inCategories = categories.filter { it.flow == CategoryFlow.IN && !it.isSystem }
                .sortedWith(compareBy({ it.archived }, { it.sortOrder }, { it.name }))
            val systemCategories = categories.filter { it.isSystem }
                .sortedWith(compareBy({ it.sortOrder }, { it.name }))

            LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                item { SectionHeader("Out") }
                items(outCategories, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        onEdit = { editing = category },
                        onArchive = { viewModel.archive(category.id) },
                        onUnarchive = { viewModel.unarchive(category) },
                    )
                }
                item { SectionHeader("In") }
                items(inCategories, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        onEdit = { editing = category },
                        onArchive = { viewModel.archive(category.id) },
                        onUnarchive = { viewModel.unarchive(category) },
                    )
                }
                item { SectionHeader("System") }
                items(systemCategories, key = { it.id }) { category ->
                    CategoryRow(category = category, onEdit = { editing = category }, onArchive = null, onUnarchive = null)
                }
            }
        }
    }

    if (adding) {
        CategoryEditDialog(
            initial = null,
            defaultFlow = CategoryFlow.OUT,
            onDismiss = { adding = false },
            onSave = { name, flow, forecastMode, colorArgb ->
                viewModel.add(name, flow, forecastMode, colorArgb)
                adding = false
            },
        )
    }
    editing?.let { category ->
        CategoryEditDialog(
            initial = category,
            defaultFlow = category.flow,
            onDismiss = { editing = null },
            onSave = { name, flow, forecastMode, colorArgb ->
                viewModel.update(
                    if (category.isSystem) {
                        category.copy(colorArgb = colorArgb)
                    } else {
                        category.copy(name = name, flow = flow, forecastMode = forecastMode, colorArgb = colorArgb)
                    },
                )
                editing = null
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CategoryRow(
    category: Category,
    onEdit: () -> Unit,
    onArchive: (() -> Unit)?,
    onUnarchive: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color((category.colorArgb ?: 0xFF9E9E9E).toInt())),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (category.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (category.forecastMode == ForecastMode.LEARNED) "Learned from history" else "Plan only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (category.archived) {
            Text(
                text = "Archived",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit ${category.name}") }
        if (category.archived) {
            onUnarchive?.let { TextButton(onClick = it) { Text("Restore") } }
        } else {
            onArchive?.let { TextButton(onClick = it) { Text("Archive") } }
        }
    }
}

@Composable
private fun CategoryEditDialog(
    initial: Category?,
    defaultFlow: CategoryFlow,
    onDismiss: () -> Unit,
    onSave: (name: String, flow: CategoryFlow, forecastMode: ForecastMode, colorArgb: Long?) -> Unit,
) {
    val isSystem = initial?.isSystem == true
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var flow by remember { mutableStateOf(initial?.flow ?: defaultFlow) }
    var forecastMode by remember { mutableStateOf(initial?.forecastMode ?: ForecastMode.LEARNED) }
    var colorArgb by remember { mutableStateOf(initial?.colorArgb ?: CATEGORY_COLOR_PALETTE.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add category" else "Edit category") },
        text = {
            Column {
                if (!isSystem) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Direction", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DirectionOption(label = "Out", selected = flow == CategoryFlow.OUT, onSelect = { flow = CategoryFlow.OUT })
                        DirectionOption(label = "In", selected = flow == CategoryFlow.IN, onSelect = { flow = CategoryFlow.IN })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Forecasting", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = forecastMode == ForecastMode.LEARNED, onClick = { forecastMode = ForecastMode.LEARNED })
                        Text("Learned from my history", modifier = Modifier.clickable { forecastMode = ForecastMode.LEARNED })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = forecastMode == ForecastMode.SCHEDULED_ONLY,
                            onClick = { forecastMode = ForecastMode.SCHEDULED_ONLY },
                        )
                        Text("Only from my plan", modifier = Modifier.clickable { forecastMode = ForecastMode.SCHEDULED_ONLY })
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The Untracked category can't be renamed or removed; you can change its colour.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CATEGORY_COLOR_PALETTE.forEach { argb ->
                        val isSelected = argb == colorArgb
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb.toInt()))
                                .border(
                                    width = if (isSelected) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                )
                                .clickable { colorArgb = argb },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), flow, forecastMode, colorArgb) },
                enabled = isSystem || name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DirectionOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onSelect)) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}
