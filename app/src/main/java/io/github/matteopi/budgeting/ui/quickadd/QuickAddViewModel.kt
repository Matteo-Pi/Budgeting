package io.github.matteopi.budgeting.ui.quickadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.entry.AmountEntry
import io.github.matteopi.budgeting.core.entry.CategoryRanking
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.DefaultAccount
import io.github.matteopi.budgeting.data.repo.PresetRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives [QuickAddScreen], used both by [QuickAddActivity] (the widget's "+" and the app's
 * `onAddTransaction`). See docs/PLAN.md §6.6.
 */
class QuickAddViewModel(
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val presetRepository: PresetRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
    initialPresetId: Long?,
    initialFlow: CategoryFlow?,
) : ViewModel() {

    private data class RepoState(
        val accounts: List<Account>,
        val categories: List<Category>,
        val transactions: List<Transaction>,
        val presets: List<Preset>,
    )

    private val repoState = combine(
        accountRepository.observeAccounts(),
        categoryRepository.observeCategories(),
        transactionRepository.observeAll(),
        presetRepository.observePresets(),
    ) { accounts, categories, transactions, presets -> RepoState(accounts, categories, transactions, presets) }

    private val flowState = MutableStateFlow(initialFlow ?: CategoryFlow.OUT)
    private val amountEntryState = MutableStateFlow(AmountEntry())
    private val selectedCategoryIdState = MutableStateFlow<Long?>(null)
    private val selectedAccountIdState = MutableStateFlow<Long?>(null)
    private val labelState = MutableStateFlow("")
    private val dateState = MutableStateFlow(clock.today())
    private val savingState = MutableStateFlow(false)

    private data class EntryState(
        val flow: CategoryFlow,
        val amountEntry: AmountEntry,
        val categoryId: Long?,
        val accountId: Long?,
        val label: String,
    )

    private val entryState = combine(
        flowState,
        amountEntryState,
        selectedCategoryIdState,
        selectedAccountIdState,
        labelState,
    ) { flow, amount, categoryId, accountId, label -> EntryState(flow, amount, categoryId, accountId, label) }

    val uiState: StateFlow<QuickAddUiState> = combine(
        repoState,
        entryState,
        combine(dateState, savingState) { date, saving -> date to saving },
    ) { repo, entry, dateAndSaving ->
        val (date, saving) = dateAndSaving
        val today = clock.today()
        val ranked = CategoryRanking.rank(repo.categories, repo.transactions, entry.flow, today)
        QuickAddUiState(
            noAccounts = repo.accounts.isEmpty(),
            flow = entry.flow,
            amountEntry = entry.amountEntry,
            rankedCategories = ranked.take(TOP_CATEGORY_COUNT),
            moreCategories = ranked.drop(TOP_CATEGORY_COUNT),
            selectedCategoryId = entry.categoryId,
            accounts = repo.accounts,
            selectedAccountId = entry.accountId,
            presets = repo.presets,
            label = entry.label,
            date = date,
            today = today,
            saving = saving,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        QuickAddUiState(date = clock.today(), today = clock.today()),
    )

    init {
        viewModelScope.launch {
            val repo = repoState.first()
            val defaultAccountId = settingsStore.budgetSettings.first().defaultAccountId
            selectedAccountIdState.value = DefaultAccount.resolveId(repo.accounts, defaultAccountId)
            val preset = initialPresetId?.let { id -> repo.presets.firstOrNull { it.id == id } }
            if (preset != null) applyPreset(preset)
        }
    }

    fun setFlow(flow: CategoryFlow) {
        flowState.value = flow
        // A category picked for the other direction no longer applies.
        selectedCategoryIdState.value = null
    }

    fun onKeypadKey(raw: String) {
        val key = KeypadMapping.toKeypadKey(raw) ?: return
        amountEntryState.value = amountEntryState.value.press(key)
    }

    fun selectCategory(categoryId: Long) {
        selectedCategoryIdState.value = categoryId
    }

    fun selectAccount(accountId: Long) {
        selectedAccountIdState.value = accountId
    }

    fun setLabel(label: String) {
        labelState.value = label
    }

    fun setDate(date: LocalDate) {
        dateState.value = date
    }

    /** Fills every field from [preset]; the user can still adjust before saving. */
    fun applyPreset(preset: Preset) {
        flowState.value = preset.flow
        amountEntryState.value = AmountEntry(moneyToEntryText(preset.amount))
        selectedCategoryIdState.value = preset.categoryId
        if (preset.accountId != null) selectedAccountIdState.value = preset.accountId
        labelState.value = preset.label
    }

    /** No-op unless amount, category and account are all set; calls [onSaved] once the write completes. */
    fun save(onSaved: () -> Unit) {
        if (savingState.value) return
        val amount = amountEntryState.value.money ?: return
        val categoryId = selectedCategoryIdState.value ?: return
        val accountId = selectedAccountIdState.value ?: return
        savingState.value = true
        viewModelScope.launch {
            val signed = if (flowState.value == CategoryFlow.OUT) -amount.abs() else amount.abs()
            transactionRepository.add(
                Transaction(
                    accountId = accountId,
                    amount = signed,
                    date = dateState.value,
                    createdAtEpochMillis = clock.nowMillis(),
                    categoryId = categoryId,
                    label = labelState.value.trim(),
                    kind = TxKind.NORMAL,
                ),
            )
            savingState.value = false
            onSaved()
        }
    }

    companion object {
        private const val TOP_CATEGORY_COUNT = 8

        fun factory(container: AppContainer, presetId: Long?, flow: CategoryFlow?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    QuickAddViewModel(
                        accountRepository = container.accountRepository,
                        categoryRepository = container.categoryRepository,
                        transactionRepository = container.transactionRepository,
                        presetRepository = container.presetRepository,
                        settingsStore = container.settingsStore,
                        clock = container.clock,
                        initialPresetId = presetId,
                        initialFlow = flow,
                    )
                }
            }
    }
}
