package io.github.matteopi.budgeting.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.domain.ForecastService
import io.github.matteopi.budgeting.domain.ForecastSnapshot
import io.github.matteopi.budgeting.domain.snapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A fund plus its live reservation figures from the forecast, for [SinkingFundsScreen]. */
data class SinkingFundRow(
    val fund: SinkingFund,
    val reservedToday: Money,
    val weeklyContribution: Money,
    val progress: Float,
    val overdue: Boolean,
)

data class SinkingFundsUiState(
    val active: List<SinkingFundRow> = emptyList(),
    val other: List<SinkingFundRow> = emptyList(),
    val loading: Boolean = true,
)

class SinkingFundsViewModel(
    forecastService: ForecastService,
) : ViewModel() {
    // Funds, today's date and their reservations all come from ONE forecast snapshot.
    val uiState: StateFlow<SinkingFundsUiState> = forecastService.state
        .map { state -> buildUiState(state.snapshot()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SinkingFundsUiState())

    private fun buildUiState(snapshot: ForecastSnapshot?): SinkingFundsUiState {
        if (snapshot == null) return SinkingFundsUiState()
        val today = snapshot.input.today
        val reservations = snapshot.result?.fundReservations?.associateBy { it.fundId } ?: emptyMap()
        val rows = snapshot.input.sinkingFunds.map { fund ->
            val reservation = reservations[fund.id]
            val reservedToday = reservation?.reservedToday ?: Money.ZERO
            val progress = if (fund.target.pence > 0) {
                (reservedToday.pence.toFloat() / fund.target.pence.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            SinkingFundRow(
                fund = fund,
                reservedToday = reservedToday,
                weeklyContribution = reservation?.weeklyContribution ?: Money.ZERO,
                progress = progress,
                overdue = fund.status == SinkingFundStatus.ACTIVE && fund.targetDate.isBefore(today),
            )
        }.sortedBy { it.fund.targetDate }
        return SinkingFundsUiState(
            active = rows.filter { it.fund.status == SinkingFundStatus.ACTIVE },
            other = rows.filter { it.fund.status != SinkingFundStatus.ACTIVE },
            loading = snapshot.result == null,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                SinkingFundsViewModel(forecastService = app.container.forecastService)
            }
        }
    }
}
