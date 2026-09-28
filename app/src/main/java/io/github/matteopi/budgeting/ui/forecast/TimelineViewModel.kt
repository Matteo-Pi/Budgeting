package io.github.matteopi.budgeting.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class TimelineUiState(
    val loading: Boolean = true,
    val weeks: List<TimelineWeek> = emptyList(),
)

class TimelineViewModel(container: AppContainer) : ViewModel() {
    val uiState: StateFlow<TimelineUiState> = combine(
        container.forecastService.forecast,
        container.planRepository.observeTermPeriods(),
        container.settingsStore.budgetSettings,
    ) { result, terms, settings ->
        if (result == null) {
            TimelineUiState(loading = true)
        } else {
            TimelineUiState(loading = false, weeks = TimelineMapping.buildWeeks(result, terms, settings.zeroPoint))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                TimelineViewModel(app.container)
            }
        }
    }
}
