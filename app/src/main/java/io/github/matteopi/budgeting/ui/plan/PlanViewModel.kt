package io.github.matteopi.budgeting.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.ForecastService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Counts and short summaries for the Plan hub's sections (docs/PLAN.md §6.5). */
data class PlanUiState(
    val moneyInCount: Int = 0,
    val regularCostsCount: Int = 0,
    val transfersCount: Int = 0,
    val oneOffCount: Int = 0,
    val termCount: Int = 0,
    val todayRegimeLabel: String = "",
    val activeSinkingFundsCount: Int = 0,
    val needsPredictionCount: Int = 0,
    val loading: Boolean = true,
)

private data class PlanData(
    val recurringRules: List<RecurringRule>,
    val scheduledItems: List<ScheduledItem>,
    val termPeriods: List<TermPeriod>,
    val activeSinkingFunds: Int,
)

class PlanViewModel(
    planRepository: PlanRepository,
    forecastService: ForecastService,
    clock: Clock,
) : ViewModel() {
    val uiState: StateFlow<PlanUiState> = combine(
        combine(
            planRepository.observeRecurringRules(),
            planRepository.observeScheduledItems(),
            planRepository.observeTermPeriods(),
            planRepository.observeSinkingFunds(),
        ) { rules, items, terms, funds ->
            PlanData(rules, items, terms, funds.count { it.status == SinkingFundStatus.ACTIVE })
        },
        forecastService.forecast,
        clock.todayFlow,
    ) { data, forecast, today ->
        val activeRules = data.recurringRules.filter { it.active }
        val upcomingItems = data.scheduledItems.filter { !it.posted && !it.skipped }
        PlanUiState(
            moneyInCount = activeRules.count { directionOf(it.amount, it.toAccountId) == Direction.IN } +
                upcomingItems.count { directionOf(it.amount, it.toAccountId) == Direction.IN },
            regularCostsCount = activeRules.count { directionOf(it.amount, it.toAccountId) == Direction.OUT },
            transfersCount = activeRules.count { directionOf(it.amount, it.toAccountId) == Direction.TRANSFER },
            oneOffCount = upcomingItems.size,
            termCount = data.termPeriods.size,
            todayRegimeLabel = todayRegimeLabel(today, data.termPeriods),
            activeSinkingFundsCount = data.activeSinkingFunds,
            needsPredictionCount = forecast?.needsPrediction?.size ?: 0,
            loading = forecast == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    private fun todayRegimeLabel(today: LocalDate, terms: List<TermPeriod>): String {
        val term = Regimes.termContaining(today, terms)
        return if (term != null) "Today: term (${term.name})" else "Today: holiday"
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                PlanViewModel(
                    planRepository = app.container.planRepository,
                    forecastService = app.container.forecastService,
                    clock = app.container.clock,
                )
            }
        }
    }
}

/** Formats a date as e.g. "28 Sep". */
fun formatShortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM"))
