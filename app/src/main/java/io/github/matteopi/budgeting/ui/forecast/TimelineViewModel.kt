package io.github.matteopi.budgeting.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.resolveForChart
import io.github.matteopi.budgeting.domain.snapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TimelineUiState(
    val loading: Boolean = true,
    val weeks: List<TimelineWeek> = emptyList(),
    /** The non-archived accounts, for the account selector above the weeks. */
    val accounts: List<Account> = emptyList(),
    /** The account view charted (the persisted one, made safe for the accounts that exist). */
    val view: AccountView = AccountView.Total,
    /** Set when the latest forecast run failed; any [weeks] are then from the last good one. */
    val error: String? = null,
)

class TimelineViewModel(private val container: AppContainer) : ViewModel() {
    // Weeks, terms, accounts and the zero point all come from ONE forecast snapshot.
    val uiState: StateFlow<TimelineUiState> = combine(
        container.forecastService.state,
        container.settingsStore.appSettings,
    ) { forecastState, appSettings ->
        val snapshot = forecastState.snapshot()
        val result = snapshot?.result
        if (snapshot == null) {
            TimelineUiState(loading = true)
        } else if (result == null) {
            TimelineUiState(loading = false, error = snapshot.error)
        } else {
            val input = snapshot.input
            val accounts = input.accounts.filter { !it.archived }
            val view = appSettings.chartAccountView.resolveForChart(accounts)
            TimelineUiState(
                loading = false,
                weeks = TimelineMapping.buildWeeks(result, input.termPeriods, input.settings.zeroPoint, input.accounts, view),
                accounts = accounts,
                view = view,
                error = snapshot.error,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    fun selectView(view: AccountView) {
        viewModelScope.launch { container.settingsStore.setChartAccountView(view) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                TimelineViewModel(app.container)
            }
        }
    }
}
