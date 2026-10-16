package io.github.matteopi.budgeting.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.importer.CategorySuggester
import io.github.matteopi.budgeting.core.importer.CheckAnswer
import io.github.matteopi.budgeting.core.importer.CsvParser
import io.github.matteopi.budgeting.core.importer.CsvTable
import io.github.matteopi.budgeting.core.importer.ImportChanges
import io.github.matteopi.budgeting.core.importer.ImportCheckInput
import io.github.matteopi.budgeting.core.importer.ImportChecks
import io.github.matteopi.budgeting.core.importer.ImportDedupe
import io.github.matteopi.budgeting.core.importer.ImportPlan
import io.github.matteopi.budgeting.core.importer.ImportQuestion
import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.ParsedStatement
import io.github.matteopi.budgeting.core.importer.PdfCells
import io.github.matteopi.budgeting.core.importer.PdfTextChunk
import io.github.matteopi.budgeting.core.importer.PossibleDuplicate
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
import io.github.matteopi.budgeting.core.model.Transaction
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

enum class ImportStep { PICK_FILE, MAPPING, REVIEW, CHECK, CATEGORISE, SUMMARY }

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

/**
 * The check step's state (docs/PLAN.md §6.5): what the questions were worked out from, the [questions] (the
 * balance gap among them follows the answers), the user's [answers] by question id, and the [plan] they make.
 */
data class CheckState(
    val input: ImportCheckInput,
    val questions: List<ImportQuestion>,
    val answers: Map<String, CheckAnswer>,
    val plan: ImportPlan,
) {
    /** True once every question has an answer. */
    val complete: Boolean get() = ImportChecks.isComplete(questions, answers)

    fun withAnswers(newAnswers: Map<String, CheckAnswer>): CheckState = of(input, newAnswers)

    companion object {
        fun of(input: ImportCheckInput, answers: Map<String, CheckAnswer>): CheckState {
            val questions = ImportChecks.questions(input, answers)
            return CheckState(input, questions, answers, ImportChecks.plan(input, questions, answers))
        }
    }
}

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
    /** Rows the check step will ask about (a possible duplicate, a different amount): ticked, and decided there. */
    val askedRows: Set<Int> = emptySet(),
    /** The check step's questions and answers, once the review has been confirmed. */
    val check: CheckState? = null,
    val merchantGroups: List<MerchantGroup> = emptyList(),
    val moveHistoryStartTo: LocalDate? = null,
    val summaryBatch: ImportBatch? = null,
    /** After importing: the app's balance on the statement's last day against the statement's closing balance. */
    val balanceCheck: BalanceCheck? = null,
    /** After importing: what the check step's answers changed ("2 matched to your own entries, 1 deleted"), if anything. */
    val checksSummary: String? = null,
    /** After importing: true if the answers changed, deleted or moved transactions that were already in the app. */
    val changedExistingTransactions: Boolean = false,
    val error: String? = null,
) {
    /** True if row [index] looks like a move between the user's accounts (only PDF statements say so). */
    fun isLikelyTransfer(index: Int): Boolean = statement?.lines?.getOrNull(index)?.likelyTransfer == true

    /** True if the check step has anything to ask. */
    val hasQuestions: Boolean get() = check?.questions?.isNotEmpty() == true
}

/** Drives the statement import wizard: pick file → map columns → review → check → categorise → import. */
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
                val review = reviewFor(accountId, current.rows, current.statement)
                _state.update {
                    if (it.selectedAccountId != accountId) {
                        it
                    } else {
                        it.copy(
                            importKeys = review.keys,
                            rowStatuses = review.statuses,
                            rowSelected = review.selected,
                            askedRows = review.asked,
                            check = null,
                        )
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
                check = null,
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
        val review = reviewFor(accountId, rows, statement)
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
                askedRows = review.asked,
                check = null,
                merchantGroups = emptyList(),
                summaryBatch = null,
                balanceCheck = null,
                checksSummary = null,
                changedExistingTransactions = false,
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
            val review = reviewFor(accountId, outcome.rows, null)
            _state.update {
                it.copy(
                    step = ImportStep.REVIEW,
                    statement = null,
                    rows = outcome.rows,
                    rowErrorCount = outcome.errors.size,
                    importKeys = review.keys,
                    rowStatuses = review.statuses,
                    rowSelected = review.selected,
                    askedRows = review.asked,
                    check = null,
                    merchantGroups = emptyList(),
                    summaryBatch = null,
                    balanceCheck = null,
                    checksSummary = null,
                    changedExistingTransactions = false,
                )
            }
        }
    }

    private class Review(
        val keys: List<String>,
        val statuses: List<RowStatus>,
        val selected: List<Boolean>,
        val asked: Set<Int>,
    )

    /**
     * The import keys, duplicate status and default ticks of [rows] against what is already saved for [accountId],
     * and the rows the check step will ask about (those are decided there, so the review shows them ticked).
     */
    private suspend fun reviewFor(accountId: Long, rows: List<ParsedRow>, statement: ParsedStatement?): Review {
        val keys = ImportDedupe.importKeys(accountId, rows)
        val existing = container.transactionRepository.observeAll().first()
        val statuses = ImportDedupe.classify(accountId, rows, keys, existing)
        val selected = statuses.map { ImportSelection.defaultSelected(it) }
        val input = checkInput(accountId, rows, keys, statuses, selected, statement, existing)
        return Review(keys, statuses, selected, ImportChecks.flaggedRows(input))
    }

    /** What the check step looks at: this import, the whole ledger, and what the statement says about its own span and balance. */
    private suspend fun checkInput(
        accountId: Long,
        rows: List<ParsedRow>,
        keys: List<String>,
        statuses: List<RowStatus>,
        selected: List<Boolean>,
        statement: ParsedStatement?,
        existing: List<Transaction>,
    ): ImportCheckInput {
        val snapshot = _state.value
        val merchantRules = container.importRepository.observeMerchantRules().first()
        val coverage = if (statement != null) statement.periodStart to statement.periodEnd else ImportChecks.coverageOf(rows)
        val closingBalance = if (statement != null) statement.closingBalance else ImportChecks.closingBalanceOf(rows)
        return ImportCheckInput(
            accountId = accountId,
            rows = rows,
            importKeys = keys,
            statuses = statuses,
            selected = selected,
            existing = existing,
            accounts = snapshot.accounts,
            coverageStart = coverage?.first,
            coverageEnd = coverage?.second,
            closingBalance = closingBalance,
            problems = statement?.problems.orEmpty(),
            categories = snapshot.categories,
            merchantRules = merchantRules,
        )
    }

    fun toggleRowSelected(index: Int) {
        val current = _state.value
        if (!ImportSelection.isSelectable(current.rowStatuses[index]) || index in current.askedRows) return
        val updated = current.rowSelected.toMutableList()
        updated[index] = !updated[index]
        _state.update { it.copy(rowSelected = updated) }
    }

    /**
     * Next from the review: works out what to ask about. With nothing to ask, on to categorising (or, if every
     * row turns out to need no import, straight to saving); otherwise to the check step. Answers given before
     * going back to the review are kept for the questions that are still asked.
     */
    fun confirmReview() {
        val current = _state.value
        val accountId = current.selectedAccountId ?: return
        // Nothing ticked (or everything already imported): nothing to ask about or to import.
        if (current.rows.indices.none { ImportSelection.isSelectable(current.rowStatuses[it]) && current.rowSelected[it] }) return
        viewModelScope.launch {
            val existing = container.transactionRepository.observeAll().first()
            val input = checkInput(
                accountId, current.rows, current.importKeys, current.rowStatuses, current.rowSelected,
                current.statement, existing,
            )
            val first = CheckState.of(input, emptyMap())
            val kept = current.check?.answers.orEmpty().filter { (id, answer) ->
                first.questions.any { it.id == id && answer in it.choices }
            }
            val check = CheckState.of(input, ImportChecks.preAnswers(first.questions) + kept)
            if (check.questions.isEmpty()) {
                openCategorise(check)
            } else {
                _state.update { it.copy(step = ImportStep.CHECK, check = check, checksSummary = null) }
            }
        }
    }

    /** Gives [answer] to the question [questionId]; the questions (the balance gap above all) are worked out again. */
    fun answerQuestion(questionId: String, answer: CheckAnswer) {
        _state.update { state ->
            val check = state.check ?: return@update state
            val question = check.questions.firstOrNull { it.id == questionId } ?: return@update state
            if (answer !in question.choices) return@update state
            state.copy(check = check.withAnswers(check.answers + (questionId to answer)))
        }
    }

    /** The shortcut at the top of the check step: [answer] to every possible duplicate that accepts it. */
    fun answerAllPossibleDuplicates(answer: CheckAnswer = CheckAnswer.SameKeepMine) {
        _state.update { state ->
            val check = state.check ?: return@update state
            val duplicates = check.questions.filterIsInstance<PossibleDuplicate>().filter { answer in it.choices }
            if (duplicates.isEmpty()) {
                state
            } else {
                state.copy(check = check.withAnswers(check.answers + duplicates.associate { it.id to answer }))
            }
        }
    }

    /** Next from the check step, once every question has an answer. */
    fun confirmChecks() {
        val check = _state.value.check ?: return
        if (!check.complete) return
        viewModelScope.launch { openCategorise(check) }
    }

    /** Groups the rows the answers leave to import by merchant and shows the categorise step; with none, saves straight away. */
    private suspend fun openCategorise(check: CheckState) {
        val current = _state.value
        if (check.plan.importRows.isEmpty()) {
            _state.update { it.copy(check = check, merchantGroups = emptyList()) }
            runImport()
            return
        }
        val merchantRules = container.importRepository.observeMerchantRules().first()
        val groups = check.plan.importRows
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
        _state.update { it.copy(step = ImportStep.CATEGORISE, check = check, merchantGroups = groups) }
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
        viewModelScope.launch { runImport() }
    }

    /**
     * Saves everything in one database transaction: the rows the answers leave to import (as chosen in the categorise
     * step), the changes the answers make to transactions already in the app, the opening-balance move and, if asked,
     * the recorded difference. Then the summary.
     */
    private suspend fun runImport() {
        val current = _state.value
        val accountId = current.selectedAccountId ?: return
        val check = current.check
        val plan = check?.plan
        val allowed = plan?.importRows?.toSet()

        val groupByIndex = mutableMapOf<Int, MerchantGroup>()
        current.merchantGroups.forEach { group -> group.rowIndices.forEach { groupByIndex[it] = group } }

        val selectedRows = groupByIndex.entries
            .filter { allowed == null || it.key in allowed }
            .sortedBy { it.key }
            .map { (index, group) ->
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
            .filter { group -> allowed == null || group.rowIndices.any { it in allowed } }
            .filter { !it.transfer && it.remember && it.categoryId != null }
            .map { MerchantRule(merchantKey = it.merchantKey, categoryId = it.categoryId!!) }

        val outcome = container.importRepository.importWithChanges(
            accountId = accountId,
            fileName = current.fileName,
            rows = selectedRows,
            rulesToRemember = rulesToRemember,
            changes = plan?.changes ?: ImportChanges.NONE,
            nowMillis = container.clock.nowMillis(),
        )

        val earliestImported = selectedRows.minOfOrNull { it.row.date }
        val historyStart = container.settingsStore.budgetSettings.first().historyStartDate
        val moveTo = if (earliestImported != null && (historyStart == null || earliestImported.isBefore(historyStart))) {
            earliestImported
        } else {
            null
        }

        val balanceCheck = balanceCheckAfterImport(check?.input, accountId)
        val changes = plan?.changes
        _state.update {
            it.copy(
                step = ImportStep.SUMMARY,
                summaryBatch = outcome.batch,
                moveHistoryStartTo = moveTo,
                balanceCheck = balanceCheck,
                checksSummary = plan?.let { p -> CheckText.changesSummary(p, current.accounts, outcome.recordedDifference) },
                changedExistingTransactions = changes != null &&
                    (changes.updates.isNotEmpty() || changes.deletions.isNotEmpty() || changes.moves.isNotEmpty()),
            )
        }
    }

    /**
     * The app's balance for [accountId] on the statement's last day against the statement's closing balance: a PDF's
     * own, or a CSV's from its balance column when it has one.
     */
    private suspend fun balanceCheckAfterImport(input: ImportCheckInput?, accountId: Long): BalanceCheck? {
        if (input == null) return null
        val closing = input.closingBalance ?: return null
        val end = input.coverageEnd ?: return null
        val transactions = container.transactionRepository.observeAll().first()
        val inApp = Balances.accountBalance(accountId, transactions, end)
        return StatementText.balanceCheck(closing, inApp, end)
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
