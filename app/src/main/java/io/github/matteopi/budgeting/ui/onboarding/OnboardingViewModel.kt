package io.github.matteopi.budgeting.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.DefaultAccount
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.plan.Direction
import io.github.matteopi.budgeting.ui.plan.LoanInstalment
import io.github.matteopi.budgeting.ui.plan.LoanInstalments
import io.github.matteopi.budgeting.ui.plan.LoanSplit
import io.github.matteopi.budgeting.ui.plan.signedAmount
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the onboarding stepper shows or edits (docs/PLAN.md §6.5). */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val today: LocalDate = LocalDate.now(),
    val finished: Boolean = false,

    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val terms: List<TermPeriod> = emptyList(),
    val recurringRules: List<RecurringRule> = emptyList(),
    val scheduledItems: List<ScheduledItem> = emptyList(),
    val predictions: List<Prediction> = emptyList(),
    val budgetSettings: BudgetSettings = BudgetSettings(),

    // Money in step: the three student loan instalments.
    val loanInstalments: List<LoanInstalment> = emptyList(),
    val loanSplitMode: Boolean = false,
    val loanTotalText: String = "",
    val loanAccountId: Long? = null,
    val loanErrorMessage: String? = null,
) {
    /** The category new student-loan instalments use (same rule as the full Plan screen). */
    val studentLoanCategory: Category?
        get() = LoanSplit.studentLoanCategory(categories)

    /** True once the three instalments have been saved, so re-visiting this step doesn't duplicate them. */
    val studentLoanCreated: Boolean
        get() {
            val categoryId = studentLoanCategory?.id ?: return false
            return scheduledItems.any { it.categoryId == categoryId && it.isMajorIncome }
        }

    /** The three split amounts to show read-only when [loanSplitMode] is on. */
    val loanSplitAmounts: List<Money>
        get() = Money.parse(loanTotalText)?.takeIf { !it.isZero }?.let(LoanSplit::splitThree)
            ?: listOf(Money.ZERO, Money.ZERO, Money.ZERO)
}

/**
 * Drives the first-run stepper: step navigation, plus the writes each step makes as the person
 * fills it in (docs/PLAN.md §6.5). Every write goes straight to the repositories/settings store,
 * so leaving onboarding mid-way keeps what was entered.
 */
class OnboardingViewModel(
    private val accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    private val planRepository: PlanRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState(today = clock.today()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.observeAccounts().collect { accounts ->
                _state.update {
                    it.copy(
                        accounts = accounts,
                        loanAccountId = it.loanAccountId
                            ?: DefaultAccount.resolveId(accounts, it.budgetSettings.defaultAccountId),
                    )
                }
            }
        }
        viewModelScope.launch {
            categoryRepository.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories) }
            }
        }
        viewModelScope.launch {
            planRepository.observeTermPeriods().collect { terms ->
                _state.update { s ->
                    // Blank instalment dates fill in as terms are added; a date the person has
                    // already picked is never overwritten by a later term edit.
                    val defaults = InstalmentDefaults.defaultDates(terms, s.today)
                    val instalments = if (s.loanInstalments.isEmpty()) {
                        defaults.map { LoanInstalment(date = it) }
                    } else {
                        s.loanInstalments.mapIndexed { i, inst ->
                            if (inst.date == null) inst.copy(date = defaults.getOrNull(i)) else inst
                        }
                    }
                    s.copy(terms = terms, loanInstalments = instalments)
                }
            }
        }
        viewModelScope.launch {
            planRepository.observeRecurringRules().collect { rules -> _state.update { it.copy(recurringRules = rules) } }
        }
        viewModelScope.launch {
            planRepository.observeScheduledItems().collect { items -> _state.update { it.copy(scheduledItems = items) } }
        }
        viewModelScope.launch {
            planRepository.observePredictions().collect { predictions -> _state.update { it.copy(predictions = predictions) } }
        }
        viewModelScope.launch {
            settingsStore.budgetSettings.collect { settings ->
                _state.update {
                    it.copy(
                        budgetSettings = settings,
                        loanAccountId = it.loanAccountId ?: DefaultAccount.resolveId(it.accounts, settings.defaultAccountId),
                    )
                }
            }
        }
    }

    // Step navigation (used by every step's Back/Skip/Next).
    fun next() = _state.update { it.copy(step = it.step.next()) }

    fun back() = _state.update { it.copy(step = it.step.previous()) }

    // Accounts step.
    fun addAccount(name: String, type: AccountType, balance: Money, overdraft: Money, includeInPool: Boolean) {
        viewModelScope.launch {
            accountRepository.createAccount(
                account = Account(name = name.trim(), type = type, overdraftLimit = overdraft, includeInSafeToSpend = includeInPool),
                openingBalance = balance,
                date = clock.today(),
            )
        }
    }

    // Money display step.
    fun setZeroPoint(zeroPoint: ZeroPoint) {
        viewModelScope.launch { settingsStore.setZeroPoint(zeroPoint) }
    }

    fun setBuffer(buffer: Money) {
        viewModelScope.launch { settingsStore.setBuffer(buffer) }
    }

    // Term dates step.
    fun saveTerm(term: TermPeriod) {
        viewModelScope.launch {
            if (term.id == 0L) planRepository.addTermPeriod(term) else planRepository.updateTermPeriod(term)
        }
    }

    fun deleteTerm(id: Long) {
        viewModelScope.launch { planRepository.deleteTermPeriod(id) }
    }

    // Money in step: student loan instalments.
    fun setLoanSplitMode(split: Boolean) = _state.update { it.copy(loanSplitMode = split, loanErrorMessage = null) }

    fun setLoanTotalText(value: String) = _state.update { it.copy(loanTotalText = value, loanErrorMessage = null) }

    fun setLoanInstalmentDate(index: Int, date: LocalDate) = _state.update { s ->
        s.copy(
            loanInstalments = s.loanInstalments.mapIndexed { i, inst -> if (i == index) inst.copy(date = date) else inst },
            loanErrorMessage = null,
        )
    }

    fun setLoanInstalmentAmountText(index: Int, text: String) = _state.update { s ->
        s.copy(
            loanInstalments = s.loanInstalments.mapIndexed { i, inst -> if (i == index) inst.copy(amountText = text) else inst },
            loanErrorMessage = null,
        )
    }

    fun setLoanAccountId(id: Long) = _state.update { it.copy(loanAccountId = id, loanErrorMessage = null) }

    /** Creates the three CONFIRM, major-income scheduled items (same rules as the full Plan screen's helper). */
    fun saveStudentLoanInstalments() {
        val current = _state.value
        if (current.studentLoanCreated) return
        val category = current.studentLoanCategory
        if (category == null) {
            _state.update { it.copy(loanErrorMessage = "No student loan category was found — add one in Categories first.") }
            return
        }
        val accountId = current.loanAccountId
        if (accountId == null) {
            _state.update { it.copy(loanErrorMessage = "Choose an account.") }
            return
        }
        val dates = LoanInstalments.datesOrNull(current.loanInstalments)
        if (dates == null) {
            _state.update { it.copy(loanErrorMessage = LoanInstalments.MISSING_DATE_MESSAGE) }
            return
        }
        val amounts: List<Money> = if (current.loanSplitMode) {
            val total = Money.parse(current.loanTotalText)
            if (total == null || total.isZero) {
                _state.update { it.copy(loanErrorMessage = "Enter the year's total.") }
                return
            }
            LoanSplit.splitThree(total)
        } else {
            val parsed = current.loanInstalments.map { Money.parse(it.amountText) }
            if (parsed.any { it == null || it.isZero }) {
                _state.update { it.copy(loanErrorMessage = "Enter all three instalment amounts.") }
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
                        isMajorIncome = true,
                    ),
                )
            }
        }
    }

    // Money in (other income) and Regular costs steps both add plain recurring rules.
    fun addRecurringRule(rule: RecurringRule) {
        viewModelScope.launch { planRepository.addRecurringRule(rule) }
    }

    fun deleteRecurringRule(id: Long) {
        viewModelScope.launch { planRepository.deleteRecurringRule(id) }
    }

    // Weekly spending estimates step.
    fun setWeeklyEstimate(categoryId: Long, regime: Regime, amount: Money) {
        viewModelScope.launch {
            planRepository.upsertPrediction(
                Prediction(categoryId = categoryId, regime = regime, mode = PredictionMode.PRIOR, weeklyAmount = amount),
            )
        }
    }

    /** "I don't spend on this": a PRIOR of £0 for both regimes. */
    fun setNoSpend(categoryId: Long) {
        viewModelScope.launch {
            planRepository.upsertPrediction(
                Prediction(categoryId = categoryId, regime = Regime.TERM, mode = PredictionMode.PRIOR, weeklyAmount = Money.ZERO),
            )
            planRepository.upsertPrediction(
                Prediction(categoryId = categoryId, regime = Regime.HOLIDAY, mode = PredictionMode.PRIOR, weeklyAmount = Money.ZERO),
            )
        }
    }

    // Done step.
    fun finish() {
        viewModelScope.launch {
            settingsStore.setOnboardingDone(true)
            _state.update { it.copy(finished = true) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                OnboardingViewModel(
                    accountRepository = app.container.accountRepository,
                    categoryRepository = app.container.categoryRepository,
                    planRepository = app.container.planRepository,
                    settingsStore = app.container.settingsStore,
                    clock = app.container.clock,
                )
            }
        }
    }
}
