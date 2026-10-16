package io.github.matteopi.budgeting.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.activity.DirectionFilter
import io.github.matteopi.budgeting.ui.activity.TransactionEditController
import io.github.matteopi.budgeting.ui.activity.TransactionEditor
import io.github.matteopi.budgeting.ui.insights.InsightsMapping
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AccountHistoryUiState(
    val loading: Boolean = true,
    val account: Account? = null,
    val today: LocalDate = LocalDate.now(),
    /** Every account and category, for the edit sheet. */
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** The categories this account's transactions use, for the category filter. */
    val usedCategories: List<Category> = emptyList(),
    val zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
    val range: HistoryRange = HistoryRange.MONTHS_3,
    /** This account's balance at the end of each day of the range, in display terms. */
    val chartPoints: List<Pair<LocalDate, Money>> = emptyList(),
    val summary: HistorySummary? = null,
    val filters: HistoryFilters = HistoryFilters(),
    val history: AccountHistory = AccountHistory(months = emptyList(), totalRows = 0, shownRows = 0),
)

/** One account's full history: balance chart, range summary, filters and every transaction (docs/PLAN.md §6.5). */
class AccountHistoryViewModel(
    transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    settingsStore: SettingsStore,
    clock: Clock,
    private val accountId: Long,
) : ViewModel() {

    private val rangeState = MutableStateFlow(HistoryRange.MONTHS_3)
    private val filterState = MutableStateFlow(HistoryFilters())

    /** The filters as typed. The search field reads this rather than [uiState] so typing is never delayed by a recompute. */
    val filters: StateFlow<HistoryFilters> = filterState.asStateFlow()

    /** Hosts the edit sheet a row opens: runs its edits and holds the "change similar" question. */
    val editing = TransactionEditController(TransactionEditor(transactionRepository), viewModelScope)

    private data class Source(
        val transactions: List<Transaction>,
        val accounts: List<Account>,
        val categories: List<Category>,
        val zeroPoint: ZeroPoint,
        val today: LocalDate,
    )

    private val sourceFlow = combine(
        transactionRepository.observeAll(),
        accountRepository.observeAccounts(),
        categoryRepository.observeCategories(),
        settingsStore.budgetSettings,
        clock.todayFlow,
    ) { transactions, accounts, categories, settings, today ->
        Source(transactions, accounts, categories, settings.zeroPoint, today)
    }

    val uiState: StateFlow<AccountHistoryUiState> = combine(sourceFlow, rangeState, filterState) { source, range, filters ->
        build(source, range, filters)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountHistoryUiState())

    fun setRange(range: HistoryRange) {
        rangeState.value = range
    }

    fun setSearch(text: String) {
        filterState.value = filterState.value.copy(search = text)
    }

    fun setCategory(categoryId: Long?) {
        filterState.value = filterState.value.copy(categoryId = categoryId)
    }

    fun setDirection(direction: DirectionFilter) {
        filterState.value = filterState.value.copy(direction = direction)
    }

    fun clearFilters() {
        filterState.value = HistoryFilters()
    }

    private fun build(source: Source, range: HistoryRange, filters: HistoryFilters): AccountHistoryUiState {
        val account = source.accounts.firstOrNull { it.id == accountId }
            ?: return AccountHistoryUiState(
                loading = false,
                today = source.today,
                accounts = source.accounts,
                categories = source.categories,
                zeroPoint = source.zeroPoint,
                range = range,
                filters = filters,
            )
        val own = source.transactions.filter { it.accountId == accountId }
        val from = range.startDate(source.today, own.minOfOrNull { it.date })
        // Insights' balance lines skip archived accounts; this page can still show one, so chart it as live.
        val chartAccounts = source.accounts.map { if (it.id == accountId) it.copy(archived = false) else it }
        val chartPoints = if (own.isEmpty()) {
            emptyList()
        } else {
            InsightsMapping.balanceHistory(
                view = AccountView.Single(accountId),
                accounts = chartAccounts,
                transactions = source.transactions,
                from = from,
                to = source.today,
                zeroPoint = source.zeroPoint,
            ).firstOrNull()?.points.orEmpty()
        }
        val usedCategoryIds = own.mapNotNull { it.categoryId }.toSet()
        return AccountHistoryUiState(
            loading = false,
            account = account,
            today = source.today,
            accounts = source.accounts,
            categories = source.categories,
            usedCategories = source.categories.filter { it.id in usedCategoryIds }.sortedBy { it.name },
            zeroPoint = source.zeroPoint,
            range = range,
            chartPoints = chartPoints,
            summary = AccountHistoryMapping.summary(account, source.transactions, source.zeroPoint, from, source.today),
            filters = filters,
            history = AccountHistoryMapping.build(
                account = account,
                transactions = source.transactions,
                accounts = source.accounts,
                categories = source.categories,
                zeroPoint = source.zeroPoint,
                filters = filters,
                today = source.today,
            ),
        )
    }

    companion object {
        fun factory(accountId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                AccountHistoryViewModel(
                    transactionRepository = app.container.transactionRepository,
                    accountRepository = app.container.accountRepository,
                    categoryRepository = app.container.categoryRepository,
                    settingsStore = app.container.settingsStore,
                    clock = app.container.clock,
                    accountId = accountId,
                )
            }
        }
    }
}
