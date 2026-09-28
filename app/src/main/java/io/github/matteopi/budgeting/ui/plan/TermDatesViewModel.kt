package io.github.matteopi.budgeting.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.domain.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TermDatesUiState(
    val terms: List<TermPeriod> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val currentTermId: Long? = null,
    val currentTermName: String? = null,
    val loading: Boolean = true,
)

class TermDatesViewModel(
    private val planRepository: PlanRepository,
    clock: Clock,
) : ViewModel() {
    val uiState: StateFlow<TermDatesUiState> = combine(
        planRepository.observeTermPeriods(),
        clock.todayFlow,
    ) { terms, today ->
        val sorted = terms.sortedBy { it.startDate }
        val current = Regimes.termContaining(today, terms)
        TermDatesUiState(
            terms = sorted,
            today = today,
            currentTermId = current?.id,
            currentTermName = current?.name,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TermDatesUiState())

    fun save(term: TermPeriod) {
        viewModelScope.launch {
            if (term.id == 0L) planRepository.addTermPeriod(term) else planRepository.updateTermPeriod(term)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { planRepository.deleteTermPeriod(id) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                TermDatesViewModel(planRepository = app.container.planRepository, clock = app.container.clock)
            }
        }
    }
}
