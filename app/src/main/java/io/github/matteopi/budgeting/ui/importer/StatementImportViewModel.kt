package io.github.matteopi.budgeting.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.importer.CategorySuggester
import io.github.matteopi.budgeting.core.importer.CsvParser
import io.github.matteopi.budgeting.core.importer.CsvTable
import io.github.matteopi.budgeting.core.importer.ImportDedupe
import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.RowStatus
import io.github.matteopi.budgeting.core.importer.StatementImport
import io.github.matteopi.budgeting.core.importer.StatementMapping
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.repo.DefaultAccount
import io.github.matteopi.budgeting.data.repo.SelectedImportRow
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ImportStep { PICK_FILE, MAPPING, REVIEW, CATEGORISE, SUMMARY }

/** Rows that share a merchant, categorised together (docs/PLAN.md §6.5). */
data class MerchantGroup(
    val merchantKey: String,
    val sampleDescription: String,
    val rowIndices: List<Int>,
    val total: Money,
    val categoryId: Long?,
    val remember: Boolean,
)

data class ImportUiState(
    val step: ImportStep = ImportStep.PICK_FILE,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val pastBatches: List<ImportBatch> = emptyList(),
    val selectedAccountId: Long? = null,
    val fileName: String = "statement.csv",
    val table: CsvTable? = null,
    val mapping: StatementMapping? = null,
    val rows: List<ParsedRow> = emptyList(),
    val rowErrorCount: Int = 0,
    val importKeys: List<String> = emptyList(),
    val rowStatuses: List<RowStatus> = emptyList(),
    val rowSelected: List<Boolean> = emptyList(),
    val merchantGroups: List<MerchantGroup> = emptyList(),
    val moveHistoryStartTo: LocalDate? = null,
    val summaryBatch: ImportBatch? = null,
    val error: String? = null,
)

/** Drives the statement import wizard: pick file → map columns → review → categorise → import. */
class StatementImportViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state

    init {
        viewModelScope.launch {
            container.accountRepository.observeAccounts().collect { accounts ->
                val defaultAccountId = DefaultAccount.resolveId(
                    accounts,
                    container.settingsStore.budgetSettings.first().defaultAccountId,
                )
                _state.value = _state.value.copy(
                    accounts = accounts,
                    selectedAccountId = _state.value.selectedAccountId ?: defaultAccountId,
                )
            }
        }
        viewModelScope.launch {
            container.categoryRepository.observeCategories().collect { categories ->
                _state.value = _state.value.copy(categories = categories)
            }
        }
        viewModelScope.launch {
            container.importRepository.observeBatches().collect { batches ->
                _state.value = _state.value.copy(pastBatches = batches)
            }
        }
    }

    fun today(): LocalDate = container.clock.today()

    fun selectAccount(accountId: Long) {
        _state.value = _state.value.copy(selectedAccountId = accountId)
    }

    fun onFileLoaded(fileName: String, text: String) {
        val table = CsvParser.parse(text)
        val mapping = StatementImport.guessMapping(table)
        _state.value = _state.value.copy(
            step = ImportStep.MAPPING,
            fileName = fileName,
            table = table,
            mapping = mapping,
            error = null,
        )
    }

    fun onFileLoadFailed() {
        _state.value = _state.value.copy(error = "Couldn't read that file.")
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun updateMapping(mapping: StatementMapping) {
        _state.value = _state.value.copy(mapping = mapping)
    }

    fun backTo(step: ImportStep) {
        _state.value = _state.value.copy(step = step)
    }

    fun confirmMapping() {
        val current = _state.value
        val table = current.table ?: return
        val mapping = current.mapping ?: return
        val accountId = current.selectedAccountId ?: return
        viewModelScope.launch {
            val outcome = StatementImport.parseRows(table, mapping)
            val keys = ImportDedupe.importKeys(accountId, outcome.rows)
            val existing = container.transactionRepository.observeAll().first()
            val statuses = ImportDedupe.classify(accountId, outcome.rows, keys, existing)
            val selected = statuses.map { ImportSelection.defaultSelected(it) }
            _state.value = current.copy(
                step = ImportStep.REVIEW,
                rows = outcome.rows,
                rowErrorCount = outcome.errors.size,
                importKeys = keys,
                rowStatuses = statuses,
                rowSelected = selected,
            )
        }
    }

    fun toggleRowSelected(index: Int) {
        val current = _state.value
        if (!ImportSelection.isSelectable(current.rowStatuses[index])) return
        val updated = current.rowSelected.toMutableList()
        updated[index] = !updated[index]
        _state.value = current.copy(rowSelected = updated)
    }

    fun confirmReview() {
        val current = _state.value
        viewModelScope.launch {
            val merchantRules = container.importRepository.observeMerchantRules().first()
            val selectedIndices = current.rows.indices.filter {
                ImportSelection.isSelectable(current.rowStatuses[it]) && current.rowSelected[it]
            }
            val groups = selectedIndices
                .groupBy { MerchantKey.normalise(current.rows[it].description) }
                .map { (key, indices) ->
                    val suggestion = CategorySuggester.suggest(key, merchantRules, current.categories)
                    MerchantGroup(
                        merchantKey = key,
                        sampleDescription = current.rows[indices.first()].description,
                        rowIndices = indices,
                        total = Money(indices.sumOf { i -> current.rows[i].amount.pence }),
                        categoryId = suggestion,
                        remember = suggestion != null,
                    )
                }
                .sortedByDescending { it.total.abs().pence }
            _state.value = current.copy(step = ImportStep.CATEGORISE, merchantGroups = groups)
        }
    }

    fun setGroupCategory(merchantKey: String, categoryId: Long?) {
        val current = _state.value
        val updated = current.merchantGroups.map {
            if (it.merchantKey == merchantKey) it.copy(categoryId = categoryId, remember = categoryId != null) else it
        }
        _state.value = current.copy(merchantGroups = updated)
    }

    fun setGroupRemember(merchantKey: String, remember: Boolean) {
        val current = _state.value
        val updated = current.merchantGroups.map { if (it.merchantKey == merchantKey) it.copy(remember = remember) else it }
        _state.value = current.copy(merchantGroups = updated)
    }

    fun confirmImport() {
        val current = _state.value
        val accountId = current.selectedAccountId ?: return
        viewModelScope.launch {
            val groupByIndex = mutableMapOf<Int, MerchantGroup>()
            current.merchantGroups.forEach { group -> group.rowIndices.forEach { groupByIndex[it] = group } }

            val selectedRows = groupByIndex.entries.sortedBy { it.key }.map { (index, group) ->
                SelectedImportRow(
                    row = current.rows[index],
                    importKey = current.importKeys[index],
                    categoryId = group.categoryId,
                )
            }
            val rulesToRemember = current.merchantGroups
                .filter { it.remember && it.categoryId != null }
                .map { MerchantRule(merchantKey = it.merchantKey, categoryId = it.categoryId!!) }

            val existing = container.transactionRepository.observeAll().first()
            val openingBalance = existing
                .filter { it.accountId == accountId && it.kind == TxKind.OPENING_BALANCE }
                // The earliest entry is the effective opening balance (later imports move it back).
                .minByOrNull { it.date }

            val batch = container.importRepository.importRows(
                accountId = accountId,
                fileName = current.fileName,
                rows = selectedRows,
                rulesToRemember = rulesToRemember,
                openingBalanceTransaction = openingBalance,
                nowMillis = container.clock.nowMillis(),
            )

            val earliestImported = selectedRows.minOfOrNull { it.row.date }
            val historyStart = container.settingsStore.budgetSettings.first().historyStartDate
            val moveTo = if (earliestImported != null && (historyStart == null || earliestImported.isBefore(historyStart))) {
                earliestImported
            } else {
                null
            }

            _state.value = current.copy(step = ImportStep.SUMMARY, summaryBatch = batch, moveHistoryStartTo = moveTo)
        }
    }

    fun moveHistoryStart(date: LocalDate) {
        viewModelScope.launch {
            container.settingsStore.setHistoryStartDate(date)
            _state.value = _state.value.copy(moveHistoryStartTo = null)
        }
    }

    fun dismissMoveHistoryStart() {
        _state.value = _state.value.copy(moveHistoryStartTo = null)
    }

    fun undoBatch(id: Long) {
        viewModelScope.launch { container.importRepository.undoBatch(id) }
    }

    /** Resets the wizard to pick another file, keeping the accounts/categories/batches already loaded. */
    fun startAnother() {
        val current = _state.value
        _state.value = ImportUiState(
            accounts = current.accounts,
            categories = current.categories,
            pastBatches = current.pastBatches,
            selectedAccountId = current.selectedAccountId,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory { StatementImportViewModel(it) }
    }
}
