package io.github.matteopi.budgeting.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.activity.ActivityGrouping
import io.github.matteopi.budgeting.ui.activity.ActivityRow
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountDetailUiState(
    val loading: Boolean = true,
    val account: Account? = null,
    val today: LocalDate = LocalDate.now(),
    val zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
    val rawBalance: Money = Money.ZERO,
    val displayBalance: Money = Money.ZERO,
    val recentRows: List<ActivityRow> = emptyList(),
    val categoriesById: Map<Long, Category> = emptyMap(),
    val accountsById: Map<Long, Account> = emptyMap(),
    val otherAccounts: List<Account> = emptyList(),
    val reconciliations: List<Reconciliation> = emptyList(),
)

/** Balances, Update balance, Transfer and recent activity for one account (docs/PLAN.md §6.5). */
class AccountDetailViewModel(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
    private val accountId: Long,
) : ViewModel() {

    private data class Core(
        val accounts: List<Account>,
        val balances: Map<Long, Money>,
        val reconciliations: List<Reconciliation>,
        val transactions: List<Transaction>,
        val zeroPoint: ZeroPoint,
    )

    private val coreFlow = combine(
        accountRepository.observeAccounts(),
        accountRepository.observeBalances(),
        accountRepository.observeReconciliationsForAccount(accountId),
        transactionRepository.observeAll(),
        settingsStore.budgetSettings,
    ) { accounts, balances, reconciliations, transactions, settings ->
        Core(accounts, balances, reconciliations, transactions, settings.zeroPoint)
    }

    val uiState: StateFlow<AccountDetailUiState> = combine(
        coreFlow,
        categoryRepository.observeCategories(),
        clock.todayFlow,
    ) { core, categories, today ->
        val account = core.accounts.firstOrNull { it.id == accountId }
        val raw = core.balances[accountId] ?: Money.ZERO
        val display = if (account != null) {
            Balances.toDisplay(raw, account.overdraftLimit, core.zeroPoint)
        } else {
            raw
        }
        val accountTransactions = core.transactions
            .filter { it.accountId == accountId }
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAtEpochMillis })
            .take(30)
        AccountDetailUiState(
            loading = false,
            account = account,
            today = today,
            zeroPoint = core.zeroPoint,
            rawBalance = raw,
            displayBalance = display,
            recentRows = ActivityGrouping.buildRows(accountTransactions),
            categoriesById = categories.associateBy { it.id },
            accountsById = core.accounts.associateBy { it.id },
            otherAccounts = core.accounts.filter { it.id != accountId && !it.archived },
            reconciliations = core.reconciliations,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailUiState())

    /** Records the bank's balance as the new truth; the gap is logged as Untracked. */
    fun updateBalance(actualBalance: Money) = viewModelScope.launch {
        accountRepository.reconcile(accountId, actualBalance)
    }

    fun transfer(toAccountId: Long, amount: Money, date: LocalDate, label: String) = viewModelScope.launch {
        transactionRepository.addTransfer(accountId, toAccountId, amount, date, label)
    }

    companion object {
        fun factory(accountId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                AccountDetailViewModel(
                    accountRepository = app.container.accountRepository,
                    transactionRepository = app.container.transactionRepository,
                    categoryRepository = app.container.categoryRepository,
                    settingsStore = app.container.settingsStore,
                    clock = app.container.clock,
                    accountId = accountId,
                )
            }
        }
    }
}
