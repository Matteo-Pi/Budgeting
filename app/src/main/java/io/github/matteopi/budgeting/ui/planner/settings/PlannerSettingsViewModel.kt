package io.github.matteopi.budgeting.ui.planner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplates
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.settings.PlannerCalendarSettings
import io.github.matteopi.budgeting.data.settings.PlannerReminderSettings
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import io.github.matteopi.budgeting.work.planner.PlannerAlarms
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlannerSettingsUiState(
    val loading: Boolean = true,
    val modules: List<Module> = emptyList(),
    val templates: List<BreakdownTemplate> = BreakdownTemplates.defaults,
    val reminders: PlannerReminderSettings = PlannerReminderSettings(),
    val terms: List<TermPeriod> = emptyList(),
    val calendars: PlannerCalendarSettings = PlannerCalendarSettings(),
    val day: DaySettings = DaySettings(),
    val categories: List<TimeCategory> = emptyList(),
)

/** The Planner's settings (docs/PLAN.md §12.11), its modules and its breakdown templates. */
class PlannerSettingsViewModel(
    private val moduleRepository: ModuleRepository,
    planRepository: PlanRepository,
    private val dayRepository: DayRepository,
    private val settingsStore: SettingsStore,
    private val plannerUpdater: PlannerUpdater,
    private val plannerAlarms: PlannerAlarms,
) : ViewModel() {

    val state: StateFlow<PlannerSettingsUiState> = combine(
        combine(moduleRepository.observeModules(), settingsStore.breakdownTemplates, settingsStore.plannerReminders) { m, t, r -> Triple(m, t, r) },
        planRepository.observeTermPeriods(),
        settingsStore.plannerCalendars,
        settingsStore.plannerDay,
        dayRepository.observeCategories(),
    ) { (modules, templates, reminders), terms, calendars, day, categories ->
        PlannerSettingsUiState(false, modules, templates, reminders, terms, calendars, day, categories)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlannerSettingsUiState())

    /** False when Android refuses exact alarms. */
    fun exactAlarmsAllowed(): Boolean = plannerAlarms.exactAlarmsAllowed()

    /** Applied to what is stored, not to the screen's copy, so quick changes in a row all count. */
    private fun setReminders(change: (PlannerReminderSettings) -> PlannerReminderSettings) {
        viewModelScope.launch {
            settingsStore.updatePlannerReminders(change)
            plannerUpdater.changed()
        }
    }

    fun setTaskReminderTime(time: LocalTime) = setReminders { it.copy(taskMinute = time.hour * 60 + time.minute) }

    fun setReviewOn(on: Boolean) = setReminders { it.copy(reviewOn = on) }

    fun setReviewTime(time: LocalTime) = setReminders { it.copy(reviewMinute = time.hour * 60 + time.minute) }

    fun setWeeklyOn(on: Boolean) = setReminders { it.copy(weeklyOn = on) }

    fun setWeeklyDay(day: DayOfWeek) = setReminders { it.copy(weeklyDay = day.value) }

    fun setWeeklyTime(time: LocalTime) = setReminders { it.copy(weeklyMinute = time.hour * 60 + time.minute) }

    // ---- The day ----

    fun setDay(day: DaySettings) {
        viewModelScope.launch {
            settingsStore.setPlannerDay(day.sanitized())
            plannerUpdater.changed()
        }
    }

    // ---- Categories ----

    /** Saves [category]; a new one goes last. */
    fun saveCategory(category: TimeCategory) {
        viewModelScope.launch {
            val order = if (category.id == 0L) (state.value.categories.maxOfOrNull { it.sortOrder } ?: -1) + 1 else category.sortOrder
            dayRepository.saveCategory(category.copy(sortOrder = order))
        }
    }

    // ---- Modules ----

    /** Saves [module]; [onDone] gets false when its code is already used. */
    fun saveModule(module: Module, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(moduleRepository.save(module) != null) }
    }

    fun deleteModule(id: Long) {
        viewModelScope.launch { moduleRepository.delete(id) }
    }

    // ---- Templates ----

    /** Replaces template [index] (or adds one when it is past the end) with [template]. */
    fun saveTemplate(index: Int, template: BreakdownTemplate) {
        val list = state.value.templates.toMutableList()
        if (index in list.indices) list[index] = template else list += template
        viewModelScope.launch { settingsStore.setBreakdownTemplates(list) }
    }

    fun deleteTemplate(index: Int) {
        val list = state.value.templates.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        viewModelScope.launch {
            // An empty list would read as the defaults again, so the last one can't go this way.
            if (list.isEmpty()) settingsStore.resetBreakdownTemplates() else settingsStore.setBreakdownTemplates(list)
        }
    }

    fun resetTemplates() {
        viewModelScope.launch { settingsStore.resetBreakdownTemplates() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            PlannerSettingsViewModel(it.moduleRepository, it.planRepository, it.dayRepository, it.settingsStore, it.plannerUpdater, it.plannerAlarms)
        }
    }
}
