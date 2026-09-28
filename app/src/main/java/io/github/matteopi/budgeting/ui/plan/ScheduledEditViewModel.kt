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

data class ScheduledEditUiState(
    val itemId: Long? = null,
    val label: String = "",
    val direction: Direction = Direction.OUT,
    val amountText: String = "",
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val date: LocalDate,
    val postingMode: PostingMode = PostingMode.AUTO,
    val isMajorIncome: Boolean = false,
    val posted: Boolean = false,
    val skipped: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val saved: Boolean = false,
    val showDeleteConfirm: Boolean = false,
)

class ScheduledEditViewModel(
    private val itemId: Long?,
    private val planRepository: PlanRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ScheduledEditUiState(itemId = itemId, date = clock.today()))
    val state: StateFlow<ScheduledEditUiState> = _state.asStateFlow()

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
        if (itemId != null) {
            viewModelScope.launch {
                val item = planRepository.observeScheduledItems().first().firstOrNull { it.id == itemId }
                if (item != null) {
                    val direction = directionOf(item.amount, item.toAccountId)
                    _state.update {
                        it.copy(
                            label = item.label,
                            direction = direction,
                            amountText = item.amount.abs().format().removePrefix("£"),
                            accountId = item.accountId,
                            toAccountId = item.toAccountId,
                            categoryId = item.categoryId,
                            date = item.date,
                            postingMode = item.postingMode,
                            isMajorIncome = item.isMajorIncome,
                            posted = item.posted,
                            skipped = item.skipped,
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

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date, errorMessage = null) }

    fun setPostingMode(mode: PostingMode) = _state.update { it.copy(postingMode = mode) }

    fun setMajorIncome(value: Boolean) = _state.update { it.copy(isMajorIncome = value) }

    fun requestDelete() = _state.update { it.copy(showDeleteConfirm = true) }

    fun dismissDelete() = _state.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        val id = _state.value.itemId ?: return
        viewModelScope.launch {
            planRepository.deleteScheduledItem(id)
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
        val item = ScheduledItem(
            id = current.itemId ?: 0,
            label = current.label.trim(),
            accountId = current.accountId!!,
            categoryId = if (current.direction == Direction.TRANSFER) null else current.categoryId,
            amount = current.direction.signedAmount(amount),
            date = current.date,
            postingMode = current.postingMode,
            toAccountId = if (current.direction == Direction.TRANSFER) current.toAccountId else null,
            isMajorIncome = current.direction == Direction.IN && current.isMajorIncome,
            posted = current.posted,
            skipped = current.skipped,
        )
        viewModelScope.launch {
            if (current.itemId == null) {
                planRepository.addScheduledItem(item)
            } else {
                planRepository.updateScheduledItem(item)
            }
            _state.update { it.copy(saved = true) }
        }
    }

    private fun validate(state: ScheduledEditUiState): String? {
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
        return null
    }

    companion object {
        fun factory(itemId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                ScheduledEditViewModel(
                    itemId = itemId,
                    planRepository = app.container.planRepository,
                    categoryRepository = app.container.categoryRepository,
                    accountRepository = app.container.accountRepository,
                    clock = app.container.clock,
                )
            }
        }
    }
}
