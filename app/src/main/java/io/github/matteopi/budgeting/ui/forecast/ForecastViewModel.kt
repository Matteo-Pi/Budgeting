package io.github.matteopi.budgeting.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.collapsePool
import io.github.matteopi.budgeting.data.settings.resolveForChart
import io.github.matteopi.budgeting.domain.snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for [ForecastScreen]. [result] and [chart] are null until the first forecast has been
 * computed (or if it failed and there is no earlier one, in which case [error] says why).
 */
data class ForecastUiState(
    val result: ForecastResult? = null,
    val chart: ForecastChartData? = null,
    /** The non-archived accounts, for the account selector above the chart. */
    val accounts: List<Account> = emptyList(),
    /** The account view actually charted (the persisted one, made safe for the accounts that exist). */
    val view: AccountView = AccountView.All,
    /** Set when the latest forecast run failed; any [result] is then the last good one. */
    val error: String? = null,
    val buffer: Money = Money.ZERO,
    val zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
    val horizon: ForecastHorizonOption = ForecastHorizonOption.WEEKS_12,
    val showTier1: Boolean = false,
)

class ForecastViewModel(private val container: AppContainer) : ViewModel() {
    private val horizonOption = MutableStateFlow(ForecastHorizonOption.WEEKS_12)
    private val showTier1 = MutableStateFlow(false)

    // The forecast, the accounts, the buffer and the zero point all come from ONE forecast
    // snapshot; only the persisted account choice and the on-screen toggles are separate.
    val uiState: StateFlow<ForecastUiState> = combine(
        container.forecastService.state,
        container.settingsStore.appSettings,
        horizonOption,
        showTier1,
    ) { forecastState, appSettings, horizon, tier1 ->
        val snapshot = forecastState.snapshot()
        if (snapshot == null) {
            ForecastUiState(horizon = horizon, showTier1 = tier1)
        } else {
            val input = snapshot.input
            val accounts = input.accounts.filter { !it.archived }
            val requested = appSettings.chartAccountView.resolveForChart(accounts).collapsePool(accounts)
            val result = snapshot.result
            val chart = result?.let {
                AccountChartMapping.build(requested, it, input.accounts, input.settings.zeroPoint, input.settings.buffer)
            }
            ForecastUiState(
                result = result,
                chart = chart,
                accounts = accounts,
                view = chart?.view ?: requested,
                error = snapshot.error,
                buffer = input.settings.buffer,
                zeroPoint = input.settings.zeroPoint,
                horizon = horizon,
                showTier1 = tier1,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForecastUiState())

    fun selectHorizon(option: ForecastHorizonOption) {
        horizonOption.value = option
    }

    fun toggleTier1() {
        showTier1.value = !showTier1.value
    }

    fun selectView(view: AccountView) {
        viewModelScope.launch { container.settingsStore.setChartAccountView(view) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                ForecastViewModel(app.container)
            }
        }
    }
}
