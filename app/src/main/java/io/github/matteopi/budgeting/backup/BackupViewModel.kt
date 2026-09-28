package io.github.matteopi.budgeting.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.backup.BackupCodec
import io.github.matteopi.budgeting.core.backup.BackupDecodeResult
import io.github.matteopi.budgeting.core.backup.BackupMapper
import io.github.matteopi.budgeting.data.settings.AppSettings
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/** What the restore flow is currently showing, from a chosen backup file. */
sealed interface RestoreState {
    data class Ready(val success: BackupDecodeResult.Success) : RestoreState
    data class Error(val errors: List<String>) : RestoreState
}

class BackupViewModel(private val container: AppContainer) : ViewModel() {
    val appSettings: StateFlow<AppSettings?> = container.settingsStore.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _restoreState = MutableStateFlow<RestoreState?>(null)
    val restoreState: StateFlow<RestoreState?> = _restoreState

    fun today(): LocalDate = container.clock.today()

    /** Builds the JSON text to write out for a backup export. */
    suspend fun buildExportJson(): String {
        val data = container.backupRepository.readAll()
        val appSettings = container.settingsStore.appSettings.first()
        val backup = BackupMapper.toBackup(
            data = data,
            exportedAt = Instant.now().toString(),
            appSettings = AppSettingsCodec.encode(appSettings),
        )
        return BackupCodec.encode(backup)
    }

    suspend fun onExported() {
        container.settingsStore.setLastExportDate(container.clock.today())
    }

    /** Decodes a chosen file's text and shows either the "replace all data?" prompt or errors. */
    fun onFileChosen(text: String) {
        _restoreState.value = when (val result = BackupCodec.decode(text)) {
            is BackupDecodeResult.Success -> RestoreState.Ready(result)
            is BackupDecodeResult.Failure -> RestoreState.Error(result.errors)
        }
    }

    fun onFileReadFailed() {
        _restoreState.value = RestoreState.Error(listOf("Couldn't read that file."))
    }

    fun clearRestoreState() {
        _restoreState.value = null
    }

    suspend fun confirmRestore(success: BackupDecodeResult.Success) {
        container.backupRepository.replaceAll(success.data)
        val current = container.settingsStore.appSettings.first()
        val restored = AppSettingsCodec.decode(success.appSettings, current)
        container.settingsStore.setHideWidgetAmounts(restored.hideWidgetAmounts)
        container.settingsStore.setAppLockEnabled(restored.appLockEnabled)
        container.settingsStore.setSecureScreen(restored.secureScreen)
        container.settingsStore.setReconcileReminderEnabled(restored.reconcileReminderEnabled)
        container.settingsStore.setReconcileReminderDay(restored.reconcileReminderDay)
        container.settingsStore.setOnboardingDone(restored.onboardingDone)
        container.widgetUpdater.requestUpdate()
        _restoreState.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory { BackupViewModel(it) }
    }
}
