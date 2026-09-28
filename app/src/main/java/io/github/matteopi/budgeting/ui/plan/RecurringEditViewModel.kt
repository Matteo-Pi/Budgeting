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
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.domain.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecurringEditUiState(
    val ruleId: Long? = null,
    val label: String = "",
    val direction: Direction = Direction.OUT,
    val amountText: String = "",
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val frequency: Frequency = Frequency.MONTHLY,
    val intervalText: String = "1",
    val startDate: LocalDate,
    val hasEndDate: Boolean = false,
    val endDate: LocalDate? = null,
    val postingMode: PostingMode = PostingMode.AUTO,
    val isMajorIncome: Boolean = false,
    val active: Boolean = true,
    val postedThrough: LocalDate? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val saved: Boolean = false,
    val showDeleteConfirm: Boolean = false,
) {
    /** The next 3 occurrence dates for the current form values, for the preview. */
    val previewDates: List<LocalDate>
        get() {
            val interval = intervalText.toIntOrNull() ?: 1
            val from = startDate
            val to = from.plusYears(10)
            return Recurrence.occurrences(frequency, interval, from, if (hasEndDate) endDate else null, from, to)
                .take(3)
        }
}

class RecurringEditViewModel(
    private val ruleId: Long?,
    private val planRepository: PlanRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(RecurringEditUiState(ruleId = ruleId, startDate = clock.today()))
    val state: StateFlow<RecurringEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(accountRepository.observeAccounts(), categoryRepository.observeCategories()) { a, c -> a to c }
                .collect { (accounts, categories) ->
                    _state.update {
                        it.copy(
                            accounts = accounts,
                            categories = categories,
                            accountId = it.accountId ?: accounts.firstOrNull()?.id,
                        )
                    }
                }
        }
        if (ruleId != null) {
            viewModelScope.launch {
                val rule = planRepository.observeRecurringRules().first().firstOrNull { it.id == ruleId }
                if (rule != null) {
                    val direction = directionOf(rule.amount, rule.toAccountId)
                    _state.update {
                        it.copy(
                            label = rule.label,
                            direction = direction,
                            amountText = rule.amount.abs().format().removePrefix("£"),
                            accountId = rule.accountId,
                            toAccountId = rule.toAccountId,
                            categoryId = rule.categoryId,
                            frequency = rule.frequency,
                            intervalText = rule.interval.toString(),
                            startDate = rule.startDate,
                            hasEndDate = rule.endDate != null,
                            endDate = rule.endDate,
                            postingMode = rule.postingMode,
                            isMajorIncome = rule.isMajorIncome,
                            active = rule.active,
                            postedThrough = rule.postedThrough,
                            loading = false,
                        )
                    }
                } else {
                    _state.update { it.copy(loading = false) }
                }
            }
        } else {
            _state.update { it.copy(loading = false) }
        }
    }

    fun setLabel(value: String) = _state.update { it.copy(label = value, errorMessage = null) }

    fun setDirection(direction: Direction) = _state.update {
        it.copy(
            direction = direction,
            postingMode = direction.defaultPostingMode(),
            categoryId = null,
            toAccountId = if (direction == Direction.TRANSFER) it.toAccountId else null,
            isMajorIncome = if (direction == Direction.IN) it.isMajorIncome else false,
            errorMessage = null,
        )
    }

    fun setAmountText(value: String) = _state.update { it.copy(amountText = value, errorMessage = null) }

    fun setAccountId(id: Long) = _state.update { it.copy(accountId = id, errorMessage = null) }

    fun setToAccountId(id: Long) = _state.update { it.copy(toAccountId = id, errorMessage = null) }

    fun setCategoryId(id: Long) = _state.update { s ->
        val category = s.categories.firstOrNull { it.id == id }
        s.copy(
            categoryId = id,
            isMajorIncome = if (s.direction == Direction.IN) (category?.isMajorIncomeDefault ?: s.isMajorIncome) else s.isMajorIncome,
            errorMessage = null,
        )
    }

    fun setFrequency(frequency: Frequency) = _state.update { it.copy(frequency = frequency, errorMessage = null) }

    fun setIntervalText(value: String) = _state.update { it.copy(intervalText = value, errorMessage = null) }

    fun setStartDate(date: LocalDate) = _state.update { it.copy(startDate = date, errorMessage = null) }

    fun setHasEndDate(has: Boolean) = _state.update {
        it.copy(hasEndDate = has, endDate = if (has) (it.endDate ?: it.startDate) else null, errorMessage = null)
    }

    fun setEndDate(date: LocalDate) = _state.update { it.copy(endDate = date, errorMessage = null) }

    fun setPostingMode(mode: PostingMode) = _state.update { it.copy(postingMode = mode) }

    fun setMajorIncome(value: Boolean) = _state.update { it.copy(isMajorIncome = value) }

    fun setActive(value: Boolean) = _state.update { it.copy(active = value) }

    fun requestDelete() = _state.update { it.copy(showDeleteConfirm = true) }

    fun dismissDelete() = _state.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        val id = _state.value.ruleId ?: return
        viewModelScope.launch {
            planRepository.deleteRecurringRule(id)
            _state.update { it.copy(saved = true, showDeleteConfirm = false) }
        }
    }

    fun save() {
        val current = _state.value
        val error = validate(current)
        if (error != null) {
            _state.update { it.copy(errorMessage = error) }
            return
        }
        val amount = Money.parse(current.amountText)!!.abs()
        val interval = current.intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val rule = RecurringRule(
            id = current.ruleId ?: 0,
            label = current.label.trim(),
            accountId = current.accountId!!,
            categoryId = if (current.direction == Direction.TRANSFER) null else current.categoryId,
            amount = current.direction.signedAmount(amount),
            frequency = current.frequency,
            interval = interval,
            startDate = current.startDate,
            endDate = if (current.hasEndDate) current.endDate else null,
            postingMode = current.postingMode,
            postedThrough = current.postedThrough,
            toAccountId = if (current.direction == Direction.TRANSFER) current.toAccountId else null,
            isMajorIncome = current.direction == Direction.IN && current.isMajorIncome,
            active = current.active,
        )
        viewModelScope.launch {
            if (current.ruleId == null) {
                planRepository.addRecurringRule(rule)
            } else {
                planRepository.updateRecurringRule(rule)
            }
            _state.update { it.copy(saved = true) }
        }
    }

    private fun validate(state: RecurringEditUiState): String? {
        if (state.label.isBlank()) return "Enter a label."
        val amount = Money.parse(state.amountText)
        if (amount == null || amount.isZero) return "Enter an amount greater than zero."
        if (state.accountId == null) return "Choose an account."
        if (state.direction == Direction.TRANSFER) {
            if (state.toAccountId == null) return "Choose a destination account."
            if (state.toAccountId == state.accountId) return "Choose a different destination account."
        } else if (state.categoryId == null) {
            return "Choose a category."
        }
        val interval = state.intervalText.toIntOrNull()
        if (interval == null || interval < 1) return "The frequency must be at least 1."
        if (state.hasEndDate && state.endDate != null && state.endDate.isBefore(state.startDate)) {
            return "The end date can't be before the start date."
        }
        return null
    }

    companion object {
        fun factory(ruleId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                RecurringEditViewModel(
                    ruleId = ruleId,
                    planRepository = app.container.planRepository,
                    categoryRepository = app.container.categoryRepository,
                    accountRepository = app.container.accountRepository,
                    clock = app.container.clock,
                )
            }
        }
    }
}
