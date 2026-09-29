package io.github.matteopi.budgeting.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.analysis.Accuracy
import io.github.matteopi.budgeting.core.analysis.AccuracyReport
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.domain.snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccuracyUiState(
    val loading: Boolean = true,
    val report: AccuracyReport? = null,
    val categories: List<Category> = emptyList(),
)

/**
 * Loads the stored [BalanceSnapshot]/[CategoryWeekSnapshot] history once (it only changes once a
 * day, written by the daily worker) and re-scores it against the ledger of the latest forecast
 * snapshot as accounts and transactions change.
 */
class AccuracyViewModel(private val container: AppContainer) : ViewModel() {
    private val snapshots = MutableStateFlow<Pair<List<BalanceSnapshot>, List<CategoryWeekSnapshot>>?>(null)

    // Accounts, transactions, categories and "today" come from ONE forecast snapshot, the same one
    // every other screen draws from, so the stored snapshots are always scored against a single ledger.
    val uiState: StateFlow<AccuracyUiState> = combine(
        container.forecastService.state,
        snapshots,
    ) { forecastState, snaps ->
        val forecast = forecastState.snapshot()
        if (snaps == null || forecast == null) {
            AccuracyUiState(loading = true)
        } else {
            val input = forecast.input
            val report = Accuracy.evaluate(snaps.first, snaps.second, input.accounts, input.transactions, input.today)
            AccuracyUiState(loading = false, report = report, categories = input.categories)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccuracyUiState())

    init {
        viewModelScope.launch {
            val balances = container.snapshotRepository.allBalanceSnapshots()
            val categoryWeeks = container.snapshotRepository.allCategorySnapshots()
            snapshots.value = balances to categoryWeeks
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                AccuracyViewModel(app.container)
            }
        }
    }
}
