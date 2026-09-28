package io.github.matteopi.budgeting.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.domain.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SinkingFundEditUiState(
    val fundId: Long? = null,
    val name: String = "",
    val targetText: String = "",
    val startDate: LocalDate,
    val targetDate: LocalDate,
    val categoryId: Long? = null,
    val status: SinkingFundStatus = SinkingFundStatus.ACTIVE,
    val categories: List<Category> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val saved: Boolean = false,
    val showDeleteConfirm: Boolean = false,
)

class SinkingFundEditViewModel(
    private val fundId: Long?,
    private val planRepository: PlanRepository,
    private val categoryRepository: CategoryRepository,
    clock: Clock,
) : ViewModel() {
    private val today = clock.today()
    private val _state = MutableStateFlow(
        SinkingFundEditUiState(fundId = fundId, startDate = today, targetDate = today.plusMonths(3)),
    )
    val state: StateFlow<SinkingFundEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            categoryRepository.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories) }
            }
        }
        if (fundId != null) {
            viewModelScope.launch {
                val fund = planRepository.observeSinkingFunds().first().firstOrNull { it.id == fundId }
                if (fund != null) {
                    _state.update {
                        it.copy(
                            name = fund.name,
                            targetText = fund.target.format().removePrefix("£"),
                            startDate = fund.startDate,
                            targetDate = fund.targetDate,
                            categoryId = fund.categoryId,
                            status = fund.status,
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

    fun setName(value: String) = _state.update { it.copy(name = value, errorMessage = null) }

    fun setTargetText(value: String) = _state.update { it.copy(targetText = value, errorMessage = null) }

    fun setStartDate(date: LocalDate) = _state.update { it.copy(startDate = date, errorMessage = null) }

    fun setTargetDate(date: LocalDate) = _state.update { it.copy(targetDate = date, errorMessage = null) }

    fun setCategoryId(id: Long?) = _state.update { it.copy(categoryId = id) }

    fun requestDelete() = _state.update { it.copy(showDeleteConfirm = true) }

    fun dismissDelete() = _state.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        val id = _state.value.fundId ?: return
        viewModelScope.launch {
            planRepository.deleteSinkingFund(id)
            _state.update { it.copy(saved = true, showDeleteConfirm = false) }
        }
    }

    fun markDone() = updateStatus(SinkingFundStatus.COMPLETED)

    fun cancelFund() = updateStatus(SinkingFundStatus.CANCELLED)

    private fun updateStatus(status: SinkingFundStatus) {
        val current = _state.value
        val id = current.fundId ?: return
        val target = Money.parse(current.targetText) ?: return
        viewModelScope.launch {
            planRepository.updateSinkingFund(
                SinkingFund(
                    id = id,
                    name = current.name.trim(),
                    target = target.abs(),
                    startDate = current.startDate,
                    targetDate = current.targetDate,
                    categoryId = current.categoryId,
                    status = status,
                ),
            )
            _state.update { it.copy(saved = true) }
        }
    }

    fun save() {
        val current = _state.value
        val error = validate(current)
        if (error != null) {
            _state.update { it.copy(errorMessage = error) }
            return
        }
        val target = Money.parse(current.targetText)!!.abs()
        val fund = SinkingFund(
            id = current.fundId ?: 0,
            name = current.name.trim(),
            target = target,
            startDate = current.startDate,
            targetDate = current.targetDate,
            categoryId = current.categoryId,
            status = current.status,
        )
        viewModelScope.launch {
            if (current.fundId == null) {
                planRepository.addSinkingFund(fund)
            } else {
                planRepository.updateSinkingFund(fund)
            }
            _state.update { it.copy(saved = true) }
        }
    }

    private fun validate(state: SinkingFundEditUiState): String? {
        if (state.name.isBlank()) return "Enter a name."
        val target = Money.parse(state.targetText)
        if (target == null || target.isZero) return "Enter a target amount greater than zero."
        if (!state.targetDate.isAfter(state.startDate)) return "The target date must be after the start date."
        return null
    }

    companion object {
        fun factory(fundId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                SinkingFundEditViewModel(
                    fundId = fundId,
                    planRepository = app.container.planRepository,
                    categoryRepository = app.container.categoryRepository,
                    clock = app.container.clock,
                )
            }
        }
    }
}

/** OUT categories usable for a sinking fund's eventual spending. */
fun outCategoriesFor(categories: List<Category>): List<Category> =
    categories.filter { it.flow == CategoryFlow.OUT && !it.archived }
