package io.github.matteopi.budgeting.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.Recurrence
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.domain.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row on [RecurringListScreen]: a rule plus everything the row needs to display. */
data class RecurringRow(
    val rule: RecurringRule,
    val direction: Direction,
    val accountName: String,
    val toAccountName: String?,
    val categoryName: String?,
    val description: String,
    val nextDate: LocalDate?,
)

data class RecurringListUiState(
    val moneyIn: List<RecurringRow> = emptyList(),
    val regularCosts: List<RecurringRow> = emptyList(),
    val transfers: List<RecurringRow> = emptyList(),
    val inactive: List<RecurringRow> = emptyList(),
    val loading: Boolean = true,
)

class RecurringListViewModel(
    planRepository: PlanRepository,
    categoryRepository: CategoryRepository,
    accountRepository: AccountRepository,
    clock: Clock,
) : ViewModel() {
    val uiState: StateFlow<RecurringListUiState> = combine(
        planRepository.observeRecurringRules(),
        categoryRepository.observeCategories(),
        accountRepository.observeAccounts(),
        clock.todayFlow,
    ) { rules, categories, accounts, today ->
        val accountsById = accounts.associateBy { it.id }
        val categoriesById = categories.associateBy { it.id }
        val rows = rules.map { rule -> rule.toRow(accountsById, categoriesById, today) }
            .sortedWith(compareBy({ it.nextDate ?: LocalDate.MAX }, { it.rule.label }))
        RecurringListUiState(
            moneyIn = rows.filter { it.rule.active && it.direction == Direction.IN },
            regularCosts = rows.filter { it.rule.active && it.direction == Direction.OUT },
            transfers = rows.filter { it.rule.active && it.direction == Direction.TRANSFER },
            inactive = rows.filter { !it.rule.active },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringListUiState())

    private fun RecurringRule.toRow(
        accountsById: Map<Long, Account>,
        categoriesById: Map<Long, Category>,
        today: LocalDate,
    ): RecurringRow {
        val direction = directionOf(amount, toAccountId)
        val from = if (startDate.isAfter(today)) startDate else today
        val nextDate = Recurrence.occurrences(frequency, interval, startDate, endDate, from, from.plusYears(5))
            .firstOrNull()
        return RecurringRow(
            rule = this,
            direction = direction,
            accountName = accountsById[accountId]?.name ?: "",
            toAccountName = toAccountId?.let { accountsById[it]?.name },
            categoryName = categoryId?.let { categoriesById[it]?.name },
            description = RecurrenceDescription.describe(frequency, interval, startDate),
            nextDate = nextDate,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                RecurringListViewModel(
                    planRepository = app.container.planRepository,
                    categoryRepository = app.container.categoryRepository,
                    accountRepository = app.container.accountRepository,
                    clock = app.container.clock,
                )
            }
        }
    }
}
