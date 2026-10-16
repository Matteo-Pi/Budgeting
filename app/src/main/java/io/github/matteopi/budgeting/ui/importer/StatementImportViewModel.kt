package io.github.matteopi.budgeting.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.importer.CategorySuggester
import io.github.matteopi.budgeting.core.importer.CsvParser
import io.github.matteopi.budgeting.core.importer.CsvTable
import io.github.matteopi.budgeting.core.importer.ImportDedupe
import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.ParsedStatement
import io.github.matteopi.budgeting.core.importer.PdfCells
import io.github.matteopi.budgeting.core.importer.PdfTextChunk
import io.github.matteopi.budgeting.core.importer.RowStatus
import io.github.matteopi.budgeting.core.importer.SantanderParseResult
import io.github.matteopi.budgeting.core.importer.SantanderStatement
import io.github.matteopi.budgeting.core.importer.StatementImport
import io.github.matteopi.budgeting.core.importer.StatementMapping
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.data.repo.DefaultAccount
import io.github.matteopi.budgeting.data.repo.ImportTransfer
import io.github.matteopi.budgeting.data.repo.SelectedImportRow
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ImportStep { PICK_FILE, MAPPING, REVIEW, CATEGORISE, SUMMARY }

/** Rows that share a merchant, categorised together (docs/PLAN.md §6.5). */
data class MerchantGroup(
    val merchantKey: String,
    val sampleDescription: String,
    val rowIndices: List<Int>,
    val total: Money,
    val categoryId: Long?,
    val remember: Boolean,
    /** A move between the user's own accounts rather than spending: no category, and no rule is remembered. */
    val transfer: Boolean = false,
    /** For a [transfer]: the user's other account at the other end, or null for an account that isn't in the app. */
    val otherAccountId: Long? = null,
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
    /** Set when the rows came from a Santander PDF statement (the mapping step is skipped for those). */
    val statement: ParsedStatement? = null,
    val rows: List<ParsedRow> = emptyList(),
    val rowErrorCount: Int = 0,
    val importKeys: List<String> = emptyList(),
    val rowStatuses: List<RowStatus> = emptyList(),
    val rowSelected: List<Boolean> = emptyList(),
    val merchantGroups: List<MerchantGroup> = emptyList(),
    val moveHistoryStartTo: LocalDate? = null,
    val summaryBatch: ImportBatch? = null,
    /** After importing a PDF statement: the app's balance on its last day against the statement's closing balance. */
    val balanceCheck: BalanceCheck? = null,
    val error: String? = null,
) {
    /** True if row [index] looks like a move between the user's accounts (only PDF statements say so). */
    fun isLikelyTransfer(index: Int): Boolean = statement?.lines?.getOrNull(index)?.likelyTransfer == true
}

/** Drives the statement import wizard: pick file → map columns → review → categorise → import. */
class StatementImportViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state

    /** The shared PDF this view model has already started loading, so a rotation does not load it again. */
    private var claimedPdfPath: String? = null

    init {
        viewModelScope.launch {
            container.accountRepository.observeAccounts().collect { accounts ->
                val defaultAccountId = DefaultAccount.resolveId(
                    accounts,
                    container.settingsStore.budgetSettings.first().defaultAccountId,
                )
                _state.update { it.copy(accounts = accounts, selectedAccountId = it.selectedAccountId ?: defaultAccountId) }
            }
        }
        viewModelScope.launch {
            container.categoryRepository.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories) }
            }
        }
        viewModelScope.launch {
            container.importRepository.observeBatches().collect { batches ->
                _state.update { it.copy(pastBatches = batches) }
            }
        }
    }

    fun today(): LocalDate = container.clock.today()

    /**
     * Chooses the account to import into. For a PDF statement that is already being reviewed the rows
     * are classified again, since "already imported" depends on the account.
     */
    fun selectAccount(accountId: Long) {
        _state.update { it.copy(selectedAccountId = accountId) }
        val current = _state.value
        if (current.statement != null && current.step == ImportStep.REVIEW) {
            viewModelScope.launch {
                val review = reviewFor(accountId, current.rows)
                _state.update {
                    if (it.selectedAccountId != accountId) {
                        it
                    } else {
                        it.copy(importKeys = review.keys, rowStatuses = review.statuses, rowSelected = review.selected)
                    }
                }
            }
        }
    }

    fun onFileLoaded(fileName: String, text: String) {
        val table = CsvParser.parse(text)
        val mapping = StatementImport.guessMapping(table)
        _state.update {
            it.copy(
                step = ImportStep.MAPPING,
                fileName = fileName,
                table = table,
                mapping = mapping,
                statement = null,
                balanceCheck = null,
                error = null,
            )
        }
    }

    /**
     * A PDF was read: [chunks] are its glyphs with their positions. A Santander statement goes straight to
     * the review (the mapping step is for CSV files); anything else shows why it can't be imported.
     */
    fun onPdfLoaded(fileName: String, chunks: List<PdfTextChunk>) {
        val cells = PdfCells.merge(chunks)
        if (!SantanderStatement.looksLikeSantander(cells)) {
            fail(StatementText.NOT_SANTANDER)
            return
        }
        when (val result = SantanderStatement.parse(cells)) {
            is SantanderParseResult.Unreadable -> fail(result.reason)
            is SantanderParseResult.Success -> viewModelScope.launch { openStatementReview(fileName, result.statement) }
        }
    }

    private suspend fun openStatementReview(fileName: String, statement: ParsedStatement) {
        // A shared statement can arrive before the accounts have loaded.
        val accountId = _state.value.selectedAccountId
            ?: withTimeoutOrNull(ACCOUNT_WAIT_MILLIS) { _state.first { it.selectedAccountId != null }.selectedAccountId }
        if (accountId == null) {
            fail("Add an account first, then import the statement into it.")
            return
        }
        val rows = statement.lines.map { it.row }
        val review = reviewFor(accountId, rows)
        _state.update {
            it.copy(
                step = ImportStep.REVIEW,
                fileName = fileName,
                table = null,
                mapping = null,
                statement = statement,
                rows = rows,
                rowErrorCount = 0,
                importKeys = review.keys,
                rowStatuses = review.statuses,
                rowSelected = review.selected,
                merchantGroups = emptyList(),
                summaryBatch = null,
                balanceCheck = null,
                error = null,
            )
        }
    }

    /** True the first time it is called for [path]; the screen loads a shared PDF only then. */
    fun claimSharedPdf(path: String): Boolean {
        if (claimedPdfPath == path) return false
        claimedPdfPath = path
        return true
    }

    fun onFileLoadFailed(message: String = "Couldn't read that file.") {
        fail(message)
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun updateMapping(mapping: StatementMapping) {
        _state.update { it.copy(mapping = mapping) }
    }

    fun backTo(step: ImportStep) {
        _state.update { it.copy(step = step) }
    }

    fun confirmMapping() {
        val current = _state.value
        val table = current.table ?: return
        val mapping = current.mapping ?: return
        val accountId = current.selectedAccountId ?: return
        viewModelScope.launch {
            val outcome = StatementImport.parseRows(table, mapping)
            val review = reviewFor(accountId, outcome.rows)
            _state.update {
                it.copy(
                    step = ImportStep.REVIEW,
                    statement = null,
                    rows = outcome.rows,
                    rowErrorCount = outcome.errors.size,
                    importKeys = review.keys,
                    rowStatuses = review.statuses,
                    rowSelected = review.selected,
                )
            }
        }
    }

    private class Review(val keys: List<String>, val statuses: List<RowStatus>, val selected: List<Boolean>)

    /** The import keys, duplicate status and default ticks of [rows] against what is already saved for [accountId]. */
    private suspend fun reviewFor(accountId: Long, rows: List<ParsedRow>): Review {
        val keys = ImportDedupe.importKeys(accountId, rows)
        val existing = container.transactionRepository.observeAll().first()
        val statuses = ImportDedupe.classify(accountId, rows, keys, existing)
        return Review(keys, statuses, statuses.map { ImportSelection.defaultSelected(it) })
    }

    fun toggleRowSelected(index: Int) {
        val current = _state.value
        if (!ImportSelection.isSelectable(current.rowStatuses[index])) return
        val updated = current.rowSelected.toMutableList()
        updated[index] = !updated[index]
        _state.update { it.copy(rowSelected = updated) }
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
                        transfer = ImportTransfers.defaultOn(indices.map { i -> current.isLikelyTransfer(i) }),
                        otherAccountId = null,
                    )
                }
                .sortedByDescending { it.total.abs().pence }
            _state.update { it.copy(step = ImportStep.CATEGORISE, merchantGroups = groups) }
        }
    }

    fun setGroupCategory(merchantKey: String, categoryId: Long?) {
        updateGroup(merchantKey) { it.copy(categoryId = categoryId, remember = categoryId != null) }
    }

    fun setGroupRemember(merchantKey: String, remember: Boolean) {
        updateGroup(merchantKey) { it.copy(remember = remember) }
    }

    /** Marks the group as a move between the user's own accounts (no category), or as ordinary spending again. */
    fun setGroupTransfer(merchantKey: String, transfer: Boolean) {
        updateGroup(merchantKey) { it.copy(transfer = transfer) }
    }

    /** For a transfer group: the user's other account at the other end, or null for one that isn't in the app. */
    fun setGroupOtherAccount(merchantKey: String, otherAccountId: Long?) {
        updateGroup(merchantKey) { it.copy(otherAccountId = otherAccountId) }
    }

    private fun updateGroup(merchantKey: String, change: (MerchantGroup) -> MerchantGroup) {
        _state.update { state ->
            state.copy(merchantGroups = state.merchantGroups.map { if (it.merchantKey == merchantKey) change(it) else it })
        }
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
                    categoryId = if (group.transfer) null else group.categoryId,
                    transfer = if (group.transfer) {
                        ImportTransfer(ImportTransfers.validOtherAccountId(group.otherAccountId, current.accounts, accountId))
                    } else {
                        null
                    },
                )
            }
            val rulesToRemember = current.merchantGroups
                .filter { !it.transfer && it.remember && it.categoryId != null }
                .map { MerchantRule(merchantKey = it.merchantKey, categoryId = it.categoryId!!) }

            val batch = container.importRepository.importRows(
                accountId = accountId,
                fileName = current.fileName,
                rows = selectedRows,
                rulesToRemember = rulesToRemember,
                nowMillis = container.clock.nowMillis(),
            )

            val earliestImported = selectedRows.minOfOrNull { it.row.date }
            val historyStart = container.settingsStore.budgetSettings.first().historyStartDate
            val moveTo = if (earliestImported != null && (historyStart == null || earliestImported.isBefore(historyStart))) {
                earliestImported
            } else {
                null
            }

            val balanceCheck = balanceCheckAfterImport(current.statement, accountId)
            _state.update {
                it.copy(
                    step = ImportStep.SUMMARY,
                    summaryBatch = batch,
                    moveHistoryStartTo = moveTo,
                    balanceCheck = balanceCheck,
                )
            }
        }
    }

    /** The app's balance for [accountId] on the statement's last day against the statement's closing balance. */
    private suspend fun balanceCheckAfterImport(statement: ParsedStatement?, accountId: Long): BalanceCheck? {
        if (statement == null) return null
        val closing = statement.closingBalance ?: return null
        val transactions = container.transactionRepository.observeAll().first()
        val inApp = Balances.accountBalance(accountId, transactions, statement.periodEnd)
        return StatementText.balanceCheck(closing, inApp, statement.periodEnd)
    }

    fun moveHistoryStart(date: LocalDate) {
        viewModelScope.launch {
            container.settingsStore.setHistoryStartDate(date)
            _state.update { it.copy(moveHistoryStartTo = null) }
        }
    }

    fun dismissMoveHistoryStart() {
        _state.update { it.copy(moveHistoryStartTo = null) }
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
        private const val ACCOUNT_WAIT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = budgetViewModelFactory { StatementImportViewModel(it) }
    }
}
