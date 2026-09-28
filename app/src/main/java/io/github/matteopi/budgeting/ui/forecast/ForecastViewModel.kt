package io.github.matteopi.budgeting.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** UI state for [ForecastScreen]. [result] is null while the first forecast is still computing. */
data class ForecastUiState(
    val result: ForecastResult? = null,
    val buffer: Money = Money.ZERO,
    val zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
    val horizon: ForecastHorizonOption = ForecastHorizonOption.WEEKS_12,
    val showTier1: Boolean = false,
)

class ForecastViewModel(private val container: AppContainer) : ViewModel() {
    private val horizonOption = MutableStateFlow(ForecastHorizonOption.WEEKS_12)
    private val showTier1 = MutableStateFlow(false)

    val uiState: StateFlow<ForecastUiState> = combine(
        container.forecastService.forecast,
        container.settingsStore.budgetSettings,
        horizonOption,
        showTier1,
    ) { result, settings, horizon, tier1 ->
        ForecastUiState(
            result = result,
            buffer = settings.buffer,
            zeroPoint = settings.zeroPoint,
            horizon = horizon,
            showTier1 = tier1,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForecastUiState())

    fun selectHorizon(option: ForecastHorizonOption) {
        horizonOption.value = option
    }

    fun toggleTier1() {
        showTier1.value = !showTier1.value
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
