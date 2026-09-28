package io.github.matteopi.budgeting.ui.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.importer.AmountMode
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.RowStatus
import io.github.matteopi.budgeting.core.importer.StatementImport
import io.github.matteopi.budgeting.core.importer.StatementMapping
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.PickerField
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.components.formatDisplayDate
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * Statement import wizard: pick file + account → map columns → review rows → categorise by
 * merchant → import, with a summary and undo (WP-U5, docs/PLAN.md §6.5, §5.16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatementImportViewModel = viewModel(factory = StatementImportViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = queryDisplayName(context, uri)
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (text != null) viewModel.onFileLoaded(name, text) else viewModel.onFileLoadFailed()
            }
        }
    }

    fun handleBack() {
        when (state.step) {
            ImportStep.PICK_FILE -> onBack()
            ImportStep.MAPPING -> viewModel.backTo(ImportStep.PICK_FILE)
            ImportStep.REVIEW -> viewModel.backTo(ImportStep.MAPPING)
            ImportStep.CATEGORISE -> viewModel.backTo(ImportStep.REVIEW)
            ImportStep.SUMMARY -> onBack()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Import statement") },
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (state.step) {
                ImportStep.PICK_FILE -> PickFileStep(
                    state = state,
                    onSelectAccount = viewModel::selectAccount,
                    onPickFile = { pickFileLauncher.launch(arrayOf("text/csv", "*/*")) },
                    onUndo = viewModel::undoBatch,
                )
                ImportStep.MAPPING -> MappingStep(
                    state = state,
                    onMappingChange = viewModel::updateMapping,
                    onNext = viewModel::confirmMapping,
                    onBack = { handleBack() },
                )
                ImportStep.REVIEW -> ReviewStep(
                    state = state,
                    onToggle = viewModel::toggleRowSelected,
                    onBack = { handleBack() },
                    onNext = viewModel::confirmReview,
                )
                ImportStep.CATEGORISE -> CategoriseStep(
                    state = state,
                    onCategoryChange = viewModel::setGroupCategory,
                    onRememberChange = viewModel::setGroupRemember,
                    onBack = { handleBack() },
                    onImport = viewModel::confirmImport,
                )
                ImportStep.SUMMARY -> SummaryStep(
                    state = state,
                    onMoveHistoryStart = viewModel::moveHistoryStart,
                    onDismissMoveHistoryStart = viewModel::dismissMoveHistoryStart,
                    onUndo = { id -> viewModel.undoBatch(id); onBack() },
                    onDone = onBack,
                )
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (it.moveToFirst() && nameIndex >= 0) {
            return it.getString(nameIndex) ?: (uri.lastPathSegment ?: "statement.csv")
        }
    }
    return uri.lastPathSegment ?: "statement.csv"
}

@Composable
private fun PickFileStep(
    state: ImportUiState,
    onSelectAccount: (Long) -> Unit,
    onPickFile: () -> Unit,
    onUndo: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = "Import a bank statement") {
            Text(
                "Choose a CSV export from your bank and the account it belongs to.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            AccountPicker(accounts = state.accounts, selectedAccountId = state.selectedAccountId, onSelect = onSelectAccount)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onPickFile, enabled = state.selectedAccountId != null) { Text("Choose file") }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.pastBatches.isNotEmpty()) {
            SectionCard(title = "Past imports") {
                state.pastBatches.forEach { batch ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(batch.fileName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${batch.rowCount} row(s)" + (batch.firstDate?.let { f -> ", from $f" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onUndo(batch.id) }) { Text("Undo") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingStep(
    state: ImportUiState,
    onMappingChange: (StatementMapping) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val table = state.table
    val mapping = state.mapping
    if (table == null || mapping == null) return

    val headerNames = if (mapping.hasHeader) table.rows.firstOrNull().orEmpty() else emptyList()
    val columnCount = table.rows.maxOfOrNull { it.size } ?: 0
    val preview = remember(table, mapping) {
        runCatching { StatementImport.parseRows(table, mapping).rows.take(5) }.getOrElse { emptyList() }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Map the columns", style = MaterialTheme.typography.titleMedium)
        ColumnPicker("Date column", columnCount, headerNames, mapping.dateColumn) { onMappingChange(mapping.copy(dateColumn = it)) }
        DatePatternPicker(mapping.datePattern) { onMappingChange(mapping.copy(datePattern = it)) }
        ColumnPicker("Description column", columnCount, headerNames, mapping.descriptionColumn) {
            onMappingChange(mapping.copy(descriptionColumn = it))
        }

        Text("Amount", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mapping.amountMode == AmountMode.SIGNED,
                onClick = { onMappingChange(mapping.copy(amountMode = AmountMode.SIGNED)) },
                label = { Text("One amount column") },
            )
            FilterChip(
                selected = mapping.amountMode == AmountMode.DEBIT_CREDIT,
                onClick = { onMappingChange(mapping.copy(amountMode = AmountMode.DEBIT_CREDIT)) },
                label = { Text("Money in / money out") },
            )
        }
        if (mapping.amountMode == AmountMode.SIGNED) {
            ColumnPicker("Amount column", columnCount, headerNames, mapping.amountColumn ?: 0) {
                onMappingChange(mapping.copy(amountColumn = it))
            }
        } else {
            ColumnPicker("Money out (debit) column", columnCount, headerNames, mapping.debitColumn ?: 0) {
                onMappingChange(mapping.copy(debitColumn = it))
            }
            ColumnPicker("Money in (credit) column", columnCount, headerNames, mapping.creditColumn ?: 0) {
                onMappingChange(mapping.copy(creditColumn = it))
            }
        }

        Text("Preview", style = MaterialTheme.typography.labelMedium)
        if (preview.isEmpty()) {
            Text("No rows parsed yet with this mapping.", style = MaterialTheme.typography.bodySmall)
        } else {
            preview.forEach { row ->
                Text("${row.date}   ${row.description}   ${row.amount.format()}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(onClick = onNext, enabled = preview.isNotEmpty()) { Text("Next") }
        }
    }
}

@Composable
private fun ColumnPicker(label: String, columnCount: Int, headerNames: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = headerNames.getOrNull(selected)?.takeIf { it.isNotBlank() } ?: "Column ${selected + 1}"
    Box {
        PickerField(
            value = display,
            label = label,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (i in 0 until columnCount) {
                DropdownMenuItem(
                    text = { Text(headerNames.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Column ${i + 1}") },
                    onClick = { onSelect(i); expanded = false },
                )
            }
        }
    }
}

private val DATE_PATTERNS = listOf("dd/MM/yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd-MM-yyyy", "d MMM yyyy", "dd MMM yyyy", "d/M/yyyy")

@Composable
private fun DatePatternPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PickerField(
            value = selected,
            label = "Date format",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DATE_PATTERNS.forEach { pattern ->
                DropdownMenuItem(text = { Text(pattern) }, onClick = { onSelect(pattern); expanded = false })
            }
        }
    }
}

@Composable
private fun ReviewStep(state: ImportUiState, onToggle: (Int) -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val visibleIndices = state.rows.indices.filter { ImportSelection.isSelectable(state.rowStatuses[it]) }
    val hiddenCount = state.rows.size - visibleIndices.size
    val selectedCount = visibleIndices.count { state.rowSelected[it] }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Review rows", style = MaterialTheme.typography.titleMedium)
            Text(
                "$selectedCount of ${visibleIndices.size} selected" +
                    (if (hiddenCount > 0) " · $hiddenCount already imported (skipped)" else "") +
                    (if (state.rowErrorCount > 0) " · ${state.rowErrorCount} row(s) couldn't be read" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (visibleIndices.isEmpty()) {
            Text(
                "Nothing new to import from this file.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(visibleIndices, key = { it }) { index ->
                    ReviewRow(
                        row = state.rows[index],
                        status = state.rowStatuses[index],
                        selected = state.rowSelected[index],
                        onToggle = { onToggle(index) },
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(onClick = onNext, enabled = selectedCount > 0) { Text("Next") }
        }
    }
}

@Composable
private fun ReviewRow(row: ParsedRow, status: RowStatus, selected: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(row.description, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatDisplayDate(row.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MoneyText(amount = row.amount)
        if (status == RowStatus.POSSIBLE_DUPLICATE) {
            Spacer(Modifier.width(8.dp))
            Text("Possible duplicate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CategoriseStep(
    state: ImportUiState,
    onCategoryChange: (String, Long?) -> Unit,
    onRememberChange: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    val categories = state.categories.filter { !it.archived }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Categorise", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(state.merchantGroups, key = { it.merchantKey }) { group ->
                MerchantGroupRow(
                    group = group,
                    categories = categories,
                    onCategoryChange = { categoryId -> onCategoryChange(group.merchantKey, categoryId) },
                    onRememberChange = { remember -> onRememberChange(group.merchantKey, remember) },
                )
            }
        }
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(onClick = onImport, enabled = state.merchantGroups.isNotEmpty()) { Text("Import") }
        }
    }
}

@Composable
private fun MerchantGroupRow(
    group: MerchantGroup,
    categories: List<Category>,
    onCategoryChange: (Long?) -> Unit,
    onRememberChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.sampleDescription, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${group.rowIndices.size} transaction(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(amount = group.total)
        }
        Spacer(Modifier.height(4.dp))
        CategoryPicker(categories = categories, selectedCategoryId = group.categoryId, onSelect = { onCategoryChange(it) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = group.remember, onCheckedChange = onRememberChange, enabled = group.categoryId != null)
            Text("Remember this category for \"${group.merchantKey}\"", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SummaryStep(
    state: ImportUiState,
    onMoveHistoryStart: (LocalDate) -> Unit,
    onDismissMoveHistoryStart: () -> Unit,
    onUndo: (Long) -> Unit,
    onDone: () -> Unit,
) {
    val batch = state.summaryBatch
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Import complete", style = MaterialTheme.typography.titleMedium)
        if (batch != null) {
            Text(
                "Imported ${batch.rowCount} transaction(s)" +
                    (batch.firstDate?.let { f -> batch.lastDate?.let { l -> " from $f to $l" } } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onUndo(batch.id) }) { Text("Undo import") }
        }
        val moveTo = state.moveHistoryStartTo
        if (moveTo != null) {
            SectionCard(title = "Learn from further back?") {
                Text(
                    "These transactions go back to $moveTo. Move the forecast's history start date there for better predictions?",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismissMoveHistoryStart) { Text("Not now") }
                    Button(onClick = { onMoveHistoryStart(moveTo) }) { Text("Move it") }
                }
            }
        }
        Button(onClick = onDone) { Text("Done") }
    }
}
