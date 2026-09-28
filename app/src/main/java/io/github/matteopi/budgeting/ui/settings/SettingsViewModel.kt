package io.github.matteopi.budgeting.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.backup.CsvExport
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AppSettings
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loaded: Boolean = false,
    val budgetSettings: BudgetSettings = BudgetSettings(),
    val appSettings: AppSettings = AppSettings(),
    val accounts: List<Account> = emptyList(),
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = combine(
        container.settingsStore.budgetSettings,
        container.settingsStore.appSettings,
        container.accountRepository.observeAccounts(),
    ) { budgetSettings, appSettings, accounts ->
        SettingsUiState(loaded = true, budgetSettings = budgetSettings, appSettings = appSettings, accounts = accounts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun today(): LocalDate = container.clock.today()

    // Settings that can change what the widget shows also ask it to refresh.
    fun setZeroPoint(zeroPoint: ZeroPoint) = updateAndRefreshWidget { container.settingsStore.setZeroPoint(zeroPoint) }
    fun setBuffer(buffer: Money) = updateAndRefreshWidget { container.settingsStore.setBuffer(buffer) }
    fun setDefaultAccountId(accountId: Long?) = updateAndRefreshWidget { container.settingsStore.setDefaultAccountId(accountId) }
    fun setHistoryStartDate(date: LocalDate?) = updateAndRefreshWidget { container.settingsStore.setHistoryStartDate(date) }
    fun setSimulations(simulations: Int) = updateAndRefreshWidget { container.settingsStore.setSimulations(simulations) }
    fun setForecastHorizonDays(days: Int) = updateAndRefreshWidget { container.settingsStore.setForecastHorizonDays(days) }
    fun setHideWidgetAmounts(hide: Boolean) = updateAndRefreshWidget { container.settingsStore.setHideWidgetAmounts(hide) }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setAppLockEnabled(enabled) }
    }

    fun setSecureScreen(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setSecureScreen(enabled) }
    }

    fun setReconcileReminderEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setReconcileReminderEnabled(enabled) }
    }

    fun setReconcileReminderDay(day: DayOfWeek) {
        viewModelScope.launch { container.settingsStore.setReconcileReminderDay(day) }
    }

    /** Builds the CSV export text and records that an export just happened. */
    suspend fun buildCsvExport(): String {
        val transactions = container.transactionRepository.observeAll().first()
        val accounts = container.accountRepository.observeAccounts().first()
        val categories = container.categoryRepository.observeCategories().first()
        return CsvExport.transactionsToCsv(transactions, accounts, categories)
    }

    suspend fun onCsvExported() {
        container.settingsStore.setLastExportDate(container.clock.today())
    }

    private fun updateAndRefreshWidget(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            container.widgetUpdater.requestUpdate()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory { SettingsViewModel(it) }
    }
}
