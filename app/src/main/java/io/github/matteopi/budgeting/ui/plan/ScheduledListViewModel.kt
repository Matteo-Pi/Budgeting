package io.github.matteopi.budgeting.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.PlanRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row on [ScheduledListScreen]. */
data class ScheduledRow(
    val item: ScheduledItem,
    val direction: Direction,
    val accountName: String,
    val toAccountName: String?,
    val categoryName: String?,
)

data class ScheduledListUiState(
    val upcoming: List<ScheduledRow> = emptyList(),
    val done: List<ScheduledRow> = emptyList(),
    val skipped: List<ScheduledRow> = emptyList(),
    val loading: Boolean = true,
)

class ScheduledListViewModel(
    planRepository: PlanRepository,
    categoryRepository: CategoryRepository,
    accountRepository: AccountRepository,
) : ViewModel() {
    val uiState: StateFlow<ScheduledListUiState> = combine(
        planRepository.observeScheduledItems(),
        categoryRepository.observeCategories(),
        accountRepository.observeAccounts(),
    ) { items, categories, accounts ->
        val accountsById = accounts.associateBy { it.id }
        val categoriesById = categories.associateBy { it.id }
        val rows = items.map { it.toRow(accountsById, categoriesById) }
            .sortedBy { it.item.date }
        ScheduledListUiState(
            upcoming = rows.filter { !it.item.posted && !it.item.skipped },
            done = rows.filter { it.item.posted },
            skipped = rows.filter { it.item.skipped && !it.item.posted },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduledListUiState())

    private fun ScheduledItem.toRow(accountsById: Map<Long, Account>, categoriesById: Map<Long, Category>): ScheduledRow =
        ScheduledRow(
            item = this,
            direction = directionOf(amount, toAccountId),
            accountName = accountsById[accountId]?.name ?: "",
            toAccountName = toAccountId?.let { accountsById[it]?.name },
            categoryName = categoryId?.let { categoriesById[it]?.name },
        )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                ScheduledListViewModel(
                    planRepository = app.container.planRepository,
                    categoryRepository = app.container.categoryRepository,
                    accountRepository = app.container.accountRepository,
                )
            }
        }
    }
}
