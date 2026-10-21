package io.github.matteopi.budgeting.ui.planner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.freetime.FreeSettings
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.suggest.StudyPrefs
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StudySettingsUiState(
    val loading: Boolean = true,
    val study: StudyPrefs = StudyPrefs(),
    val free: FreeSettings = FreeSettings(),
    /** Coming days switched off, soonest first. */
    val daysOff: List<LocalDate> = emptyList(),
    val modules: List<Module> = emptyList(),
    val today: LocalDate,
)

/** Free time and suggested study (docs/PLAN.md §12.6, §12.7, §13.6): each change is saved as it is made. */
class StudySettingsViewModel(
    private val settingsStore: SettingsStore,
    moduleRepository: ModuleRepository,
    private val plannerUpdater: PlannerUpdater,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<StudySettingsUiState> = combine(
        settingsStore.plannerStudy,
        settingsStore.plannerFree,
        settingsStore.plannerDaysOff,
        moduleRepository.observeModules(),
        clock.todayFlow,
    ) { study, free, off, modules, today ->
        StudySettingsUiState(false, study, free, off.filter { !it.isBefore(today) }.sorted(), modules.filter { !it.archived }, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudySettingsUiState(today = clock.today()))

    // Each change is applied to what is stored, not to the screen's copy, so quick taps in a row all count.

    fun setStudy(change: (StudyPrefs) -> StudyPrefs) {
        viewModelScope.launch {
            settingsStore.updatePlannerStudy { change(it).sanitized() }
            plannerUpdater.changed()
        }
    }

    fun setFree(change: (FreeSettings) -> FreeSettings) {
        viewModelScope.launch {
            settingsStore.updatePlannerFree(change)
            plannerUpdater.changed()
        }
    }

    /** Switches suggestions off for [date] (a day off), or back on. */
    fun setDayOff(date: LocalDate, off: Boolean) {
        viewModelScope.launch {
            settingsStore.setPlannerDayOff(date, off, clock.today())
            plannerUpdater.changed()
        }
    }

    fun setModuleOff(moduleId: Long, off: Boolean) = setStudy { prefs ->
        prefs.copy(offModules = if (off) (prefs.offModules + moduleId).distinct() else prefs.offModules - moduleId)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            StudySettingsViewModel(it.settingsStore, it.moduleRepository, it.plannerUpdater, it.clock)
        }
    }
}
