package io.github.matteopi.budgeting.ui.planner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.data.calendar.CalendarAccess
import io.github.matteopi.budgeting.data.calendar.CalendarInfo
import io.github.matteopi.budgeting.data.settings.CalendarChoice
import io.github.matteopi.budgeting.data.settings.PlannerCalendarSettings
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.planner.PlannerCalendar
import io.github.matteopi.budgeting.domain.planner.PlannerCalendarSync
import io.github.matteopi.budgeting.domain.planner.resolveCalendars
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarsUiState(
    val loading: Boolean = true,
    val canRead: Boolean = false,
    val canWrite: Boolean = false,
    /** The phone's calendars, by account then name. */
    val calendars: List<CalendarInfo> = emptyList(),
    val settings: PlannerCalendarSettings = PlannerCalendarSettings(),
    /** The ids of [PlannerCalendarSettings.read] found among [calendars]. */
    val readIds: Set<Long> = emptySet(),
    /** The calendar written to, when writing back is on and it is still on the phone. */
    val writeId: Long? = null,
) {
    val writable: List<CalendarInfo> get() = calendars.filter { it.writable }
}

/**
 * Linking calendars (docs/PLAN.md §12.5): which of the phone's calendars the Planner shows, and writing back to one.
 * Syncs run on the app's scope, so leaving the screen doesn't stop one halfway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarsViewModel(
    private val access: CalendarAccess,
    private val settingsStore: SettingsStore,
    private val plannerCalendar: PlannerCalendar,
    private val calendarSync: PlannerCalendarSync,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val refreshes = MutableStateFlow(0)

    val state: StateFlow<CalendarsUiState> = combine(settingsStore.plannerCalendars, refreshes) { settings, _ -> settings }
        .mapLatest { settings ->
            val calendars = access.calendars()
            CalendarsUiState(
                loading = false,
                canRead = access.canRead(),
                canWrite = access.canWrite(),
                calendars = calendars,
                settings = settings,
                readIds = resolveCalendars(settings.read, calendars).map { it.id }.toSet(),
                writeId = settings.writeTo?.let { resolveCalendars(listOf(it), calendars).firstOrNull()?.id },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarsUiState())

    /** After a permission answer or the screen coming back: the permissions and calendars are read again. */
    fun refresh() {
        refreshes.update { it + 1 }
        plannerCalendar.refresh()
    }

    fun setShown(calendar: CalendarInfo, shown: Boolean) = edit { settings ->
        val others = settings.read.filter { resolveCalendars(listOf(it), state.value.calendars).firstOrNull()?.id != calendar.id }
        settings.copy(read = if (shown) others + calendar.choice() else others)
    }

    /** Writing back to [calendar], or off with null (what was written stays; *Remove what the app added* clears it). */
    fun setWriteTo(calendar: CalendarInfo?) = edit(sync = calendar != null) { it.copy(writeTo = calendar?.choice()) }

    fun setWriteTimetable(on: Boolean) = edit(sync = true) { it.copy(writeTimetable = on) }

    /** *Remove what the app added*: [onDone] gets how many events went. */
    fun removeAll(onDone: (Int) -> Unit) {
        appScope.launch {
            val removed = calendarSync.removeAll()
            viewModelScope.launch { onDone(removed) }
        }
    }

    private fun edit(sync: Boolean = false, change: (PlannerCalendarSettings) -> PlannerCalendarSettings) {
        appScope.launch {
            settingsStore.setPlannerCalendars(change(settingsStore.plannerCalendars.first()))
            if (sync) calendarSync.sync()
        }
    }

    private fun CalendarInfo.choice() = CalendarChoice(id, accountName, name)

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            CalendarsViewModel(it.calendarAccess, it.settingsStore, it.plannerCalendar, it.plannerCalendarSync, it.appScope)
        }
    }
}
