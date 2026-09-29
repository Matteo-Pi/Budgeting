package io.github.matteopi.budgeting.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.domain.ForecastService
import io.github.matteopi.budgeting.domain.ForecastSnapshot
import io.github.matteopi.budgeting.domain.snapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

class PlanViewModel(
    forecastService: ForecastService,
) : ViewModel() {
    // Counts, today's regime and the needs-prediction badge all come from ONE forecast snapshot.
    val uiState: StateFlow<PlanUiState> = forecastService.state
        .map { state -> buildUiState(state.snapshot()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    private fun buildUiState(snapshot: ForecastSnapshot?): PlanUiState {
        if (snapshot == null) return PlanUiState()
        val input = snapshot.input
        val activeRules = input.recurringRules.filter { it.active }
        val upcomingItems = input.scheduledItems.filter { !it.posted && !it.skipped }
        return PlanUiState(
            moneyInCount = activeRules.count { directionOf(it.amount, it.toAccountId) == Direction.IN } +
                upcomingItems.count { directionOf(it.amount, it.toAccountId) == Direction.IN },
            regularCostsCount = activeRules.count { directionOf(it.amount, it.toAccountId) == Direction.OUT },
            transfersCount = activeRules.count { directionOf(it.amount, it.toAccountId) == Direction.TRANSFER },
            oneOffCount = upcomingItems.size,
            termCount = input.termPeriods.size,
            todayRegimeLabel = todayRegimeLabel(input.today, input.termPeriods),
            activeSinkingFundsCount = input.sinkingFunds.count { it.status == SinkingFundStatus.ACTIVE },
            needsPredictionCount = snapshot.result?.needsPrediction?.size ?: 0,
            loading = snapshot.result == null,
        )
    }

    private fun todayRegimeLabel(today: LocalDate, terms: List<TermPeriod>): String {
        val term = Regimes.termContaining(today, terms)
        return if (term != null) "Today: term (${term.name})" else "Today: holiday"
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                PlanViewModel(forecastService = app.container.forecastService)
            }
        }
    }
}

/** Formats a date as e.g. "28 Sep". */
// Locale.ENGLISH: en-GB CLDR data abbreviates September as "Sept".
fun formatShortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
