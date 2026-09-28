package io.github.matteopi.budgeting.ui.activity

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
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import io.github.matteopi.budgeting.domain.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActivityUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val filters: ActivityFilters = ActivityFilters(),
    val groups: List<DayGroup> = emptyList(),
    /** True when there are no transactions at all (independent of the current filters). */
    val hasNoTransactions: Boolean = false,
)

/** Activity tab state and actions (docs/PLAN.md §6.5). */
class ActivityViewModel(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    private val filters = MutableStateFlow(ActivityFilters())

    private data class Data(
        val transactions: List<Transaction>,
        val accounts: List<Account>,
        val categories: List<Category>,
        val today: LocalDate,
    )

    private val dataFlow = combine(
        transactionRepository.observeAll(),
        accountRepository.observeAccounts(),
        categoryRepository.observeCategories(),
        clock.todayFlow,
    ) { transactions, accounts, categories, today -> Data(transactions, accounts, categories, today) }

    val uiState: StateFlow<ActivityUiState> = combine(dataFlow, filters) { data, currentFilters ->
        val categoriesById = data.categories.associateBy { it.id }
        val allRows = ActivityGrouping.buildRows(data.transactions)
        val filteredRows = allRows.filter { ActivityGrouping.matches(it, currentFilters, categoriesById) }
        ActivityUiState(
            loading = false,
            today = data.today,
            accounts = data.accounts,
            categories = data.categories,
            filters = currentFilters,
            groups = ActivityGrouping.groupByDay(filteredRows, data.today),
            hasNoTransactions = allRows.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun setAccountFilter(accountId: Long?) {
        filters.value = filters.value.copy(accountId = accountId)
    }

    fun setCategoryFilter(categoryId: Long?) {
        filters.value = filters.value.copy(categoryId = categoryId)
    }

    fun setDirectionFilter(direction: DirectionFilter) {
        filters.value = filters.value.copy(direction = direction)
    }

    fun setSearch(text: String) {
        filters.value = filters.value.copy(search = text)
    }

    fun clearFilters() {
        filters.value = ActivityFilters()
    }

    fun saveSingle(
        original: Transaction,
        amount: Money,
        categoryId: Long?,
        accountId: Long,
        date: LocalDate,
        label: String,
        note: String,
    ) = viewModelScope.launch {
        transactionRepository.update(
            original.copy(
                amount = amount,
                categoryId = categoryId,
                accountId = accountId,
                date = date,
                label = label,
                note = note,
            ),
        )
    }

    /** Updates both legs of a transfer (amount/date via the repository's own mirroring, label on each leg directly). */
    fun saveTransfer(row: ActivityRow.Transfer, amount: Money, date: LocalDate, label: String) = viewModelScope.launch {
        val magnitude = amount.abs()
        transactionRepository.update(row.from.copy(amount = -magnitude, date = date, label = label))
        transactionRepository.update(row.to.copy(amount = magnitude, date = date, label = label))
    }

    fun delete(transactionId: Long) = viewModelScope.launch {
        transactionRepository.delete(transactionId)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                ActivityViewModel(
                    transactionRepository = app.container.transactionRepository,
                    accountRepository = app.container.accountRepository,
                    categoryRepository = app.container.categoryRepository,
                    clock = app.container.clock,
                )
            }
        }
    }
}
