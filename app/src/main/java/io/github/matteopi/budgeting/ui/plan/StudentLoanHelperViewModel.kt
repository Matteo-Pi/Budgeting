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
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.DefaultAccount
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.onboarding.InstalmentDefaults
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StudentLoanHelperUiState(
    val instalments: List<LoanInstalment> = emptyList(),
    val splitMode: Boolean = false,
    val totalText: String = "",
    val accounts: List<Account> = emptyList(),
    val accountId: Long? = null,
    val category: Category? = null,
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val saved: Boolean = false,
) {
    /** The three split amounts to show read-only when [splitMode] is on. */
    val splitAmounts: List<Money>
        get() = Money.parse(totalText)?.takeIf { !it.isZero }?.let(LoanSplit::splitThree) ?: listOf(Money.ZERO, Money.ZERO, Money.ZERO)
}

class StudentLoanHelperViewModel(
    private val planRepository: PlanRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(StudentLoanHelperUiState())
    val state: StateFlow<StudentLoanHelperUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val today = clock.today()
            val accounts = accountRepository.observeAccounts().first()
            val categories = categoryRepository.observeCategories().first()
            val terms = planRepository.observeTermPeriods().first()
            // Fewer than three upcoming terms: the missing instalments start blank and the person
            // must pick a date for each (a made-up date of today would create a fake "did your
            // loan arrive?" prompt straight away).
            val dates = InstalmentDefaults.defaultDates(terms, today)
            _state.update {
                it.copy(
                    instalments = dates.map { date -> LoanInstalment(date = date) },
                    accounts = accounts,
                    accountId = DefaultAccount.resolveId(accounts, settingsStore.budgetSettings.first().defaultAccountId),
                    category = LoanSplit.studentLoanCategory(categories),
                    loading = false,
                )
            }
        }
    }

    fun setSplitMode(split: Boolean) = _state.update { it.copy(splitMode = split, errorMessage = null) }

    fun setTotalText(value: String) = _state.update { it.copy(totalText = value, errorMessage = null) }

    fun setInstalmentDate(index: Int, date: LocalDate) = _state.update { s ->
        s.copy(
            instalments = s.instalments.mapIndexed { i, inst -> if (i == index) inst.copy(date = date) else inst },
            errorMessage = null,
        )
    }

    fun setInstalmentAmountText(index: Int, text: String) = _state.update { s ->
        s.copy(
            instalments = s.instalments.mapIndexed { i, inst -> if (i == index) inst.copy(amountText = text) else inst },
            errorMessage = null,
        )
    }

    fun setAccountId(id: Long) = _state.update { it.copy(accountId = id, errorMessage = null) }

    fun save() {
        val current = _state.value
        val category = current.category
        if (category == null) {
            _state.update { it.copy(errorMessage = "No student loan category was found — add one in Categories first.") }
            return
        }
        val accountId = current.accountId
        if (accountId == null) {
            _state.update { it.copy(errorMessage = "Choose an account.") }
            return
        }
        val dates = LoanInstalments.datesOrNull(current.instalments)
        if (dates == null) {
            _state.update { it.copy(errorMessage = LoanInstalments.MISSING_DATE_MESSAGE) }
            return
        }
        val amounts: List<Money> = if (current.splitMode) {
            val total = Money.parse(current.totalText)
            if (total == null || total.isZero) {
                _state.update { it.copy(errorMessage = "Enter the year's total.") }
                return
            }
            LoanSplit.splitThree(total)
        } else {
            val parsed = current.instalments.map { Money.parse(it.amountText) }
            if (parsed.any { it == null || it.isZero }) {
                _state.update { it.copy(errorMessage = "Enter all three instalment amounts.") }
                return
            }
            parsed.map { it!! }
        }
        viewModelScope.launch {
            dates.forEachIndexed { index, date ->
                planRepository.addScheduledItem(
                    ScheduledItem(
                        label = "Student loan",
                        accountId = accountId,
                        categoryId = category.id,
                        amount = Direction.IN.signedAmount(amounts[index]),
                        date = date,
                        postingMode = PostingMode.CONFIRM,
                        toAccountId = null,
                        isMajorIncome = true,
                    ),
                )
            }
            _state.update { it.copy(saved = true) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                StudentLoanHelperViewModel(
                    planRepository = app.container.planRepository,
                    accountRepository = app.container.accountRepository,
                    categoryRepository = app.container.categoryRepository,
                    settingsStore = app.container.settingsStore,
                    clock = app.container.clock,
                )
            }
        }
    }
}
