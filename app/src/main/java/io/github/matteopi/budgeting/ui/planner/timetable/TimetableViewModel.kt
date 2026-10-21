package io.github.matteopi.budgeting.ui.planner.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.planner.CalendarState
import io.github.matteopi.budgeting.domain.planner.PlannerCalendar
import io.github.matteopi.budgeting.domain.planner.SuggestionService
import io.github.matteopi.budgeting.domain.planner.SuggestionsModel
import io.github.matteopi.budgeting.domain.planner.instances
import io.github.matteopi.budgeting.ui.planner.components.UndoableEvent
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionActions
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TimetableMode { DAY, WEEK, MONTH }

data class TimetableUiState(
    val loading: Boolean = true,
    val today: LocalDate,
    val mode: TimetableMode = TimetableMode.WEEK,
    /** The day shown in day mode, and the day whose week or month is shown otherwise. */
    val focus: LocalDate,
    val week: WeekUi? = null,
    val day: List<TimetableItemUi> = emptyList(),
    val dayAllDay: List<String> = emptyList(),
    val month: List<MonthDayUi> = emptyList(),
    val calendar: CalendarState = CalendarState.Off,
    val hasEntries: Boolean = false,
    /** The term before the one shown, when it has entries of its own to copy and the shown one has none. */
    val copyFrom: TermPeriod? = null,
    val copyTo: TermPeriod? = null,
    val modules: List<Module> = emptyList(),
)

/** The Timetable tab (docs/PLAN.md §12.4, §13.9): the week grid, a day's list and the month. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModel(
    private val timetableRepository: TimetableRepository,
    taskRepository: TaskRepository,
    moduleRepository: ModuleRepository,
    planRepository: PlanRepository,
    private val plannerCalendar: PlannerCalendar,
    dayRepository: DayRepository,
    suggestionService: SuggestionService,
    private val suggestionActions: SuggestionActions,
    private val clock: Clock,
) : ViewModel() {
    private val events = Channel<UndoableEvent>(Channel.BUFFERED)

    /** What accepting or moving a suggestion did, for the *Undo* snackbar. */
    val eventFlow: Flow<UndoableEvent> = events.receiveAsFlow()

    /** The study suggestions, shown outlined on the week; null until the first run. */
    private val suggestions: StateFlow<SuggestionsModel?> = suggestionService.observe()
        .map<SuggestionsModel, SuggestionsModel?> { it }
        .onStart { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val mode = MutableStateFlow(TimetableMode.WEEK)
    private val focus = MutableStateFlow(clock.today())

    private data class Source(
        val entries: List<TimetableEntry>,
        val exceptions: List<EntryException>,
        val terms: List<TermPeriod>,
        val modules: List<Module>,
        val tasks: List<Task>,
    )

    private val source = combine(
        timetableRepository.observeEntries(),
        timetableRepository.observeExceptions(),
        planRepository.observeTermPeriods(),
        moduleRepository.observeModules(),
        taskRepository.observeOpenTasks(),
    ) { entries, exceptions, terms, modules, tasks -> Source(entries, exceptions, terms, modules, tasks) }

    /** The days on screen: the week of the focus, or its whole month grid (six weeks). */
    private fun range(mode: TimetableMode, focus: LocalDate): Pair<LocalDate, LocalDate> = when (mode) {
        TimetableMode.DAY -> focus to focus
        TimetableMode.WEEK -> TimetableMapping.weekStart(focus).let { it to it.plusDays(6) }
        TimetableMode.MONTH -> TimetableMapping.weekStart(focus.withDayOfMonth(1)).let { it to it.plusDays(41) }
    }

    private val calendar = combine(mode, focus) { m, f -> range(m, f) }.flatMapLatest { (from, to) ->
        val zone = clock.zone()
        plannerCalendar.observe(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
    }

    /** The blocks planned in the day record for the days on screen. */
    private val plans = combine(mode, focus) { m, f -> range(m, f) }.flatMapLatest { (from, to) ->
        val zone = clock.zone()
        dayRepository.observePlans(Interval(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant()))
    }

    private data class Extras(val today: LocalDate, val suggested: SuggestionsModel?, val plans: List<PlanBlock>)

    val state: StateFlow<TimetableUiState> = combine(
        source, mode, focus, calendar, combine(clock.todayFlow, suggestions, plans) { t, s, p -> Extras(t, s, p) },
    ) { src, mode, focus, cal, (today, suggested, plans) ->
        val zone = clock.zone()
        val (from, to) = range(mode, focus)
        val occurrences = Timetable.occurrences(src.entries, src.exceptions, src.terms, from, to, zone, includeCancelled = true)
        val events = cal.instances
        val shownTerm = Regimes.termContaining(focus, src.terms)
        val previous = shownTerm?.let { t -> src.terms.filter { it.endDate.isBefore(t.startDate) }.maxByOrNull { it.endDate } }
        val canCopy = shownTerm != null && previous != null &&
            src.entries.any { it.termId == previous.id && !it.archived } && src.entries.none { it.termId == shownTerm.id }
        TimetableUiState(
            loading = false,
            today = today,
            mode = mode,
            focus = focus,
            week = if (mode == TimetableMode.WEEK) {
                TimetableMapping.week(
                    TimetableMapping.weekStart(focus), today, occurrences, events, src.tasks, src.modules, src.terms, zone, suggested?.suggestions.orEmpty(),
                    plans,
                )
            } else {
                null
            },
            day = if (mode == TimetableMode.DAY) {
                TimetableMapping.dayItems(focus, occurrences, events, src.tasks, src.modules, zone, suggested?.suggestions.orEmpty(), plans)
            } else {
                emptyList()
            },
            dayAllDay = if (mode == TimetableMode.DAY) TimetableMapping.allDay(focus, events, zone) else emptyList(),
            month = if (mode == TimetableMode.MONTH) TimetableMapping.month(focus, today, occurrences, events, src.tasks, src.modules, zone) else emptyList(),
            calendar = cal,
            hasEntries = src.entries.any { !it.archived },
            copyFrom = if (canCopy) previous else null,
            copyTo = if (canCopy) shownTerm else null,
            modules = src.modules,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimetableUiState(today = clock.today(), focus = clock.today()))

    fun setMode(value: TimetableMode) {
        mode.value = value
    }

    /** Back or forward one day, week or month. */
    fun move(steps: Int) {
        focus.value = when (mode.value) {
            TimetableMode.DAY -> focus.value.plusDays(steps.toLong())
            TimetableMode.WEEK -> focus.value.plusWeeks(steps.toLong())
            TimetableMode.MONTH -> focus.value.plusMonths(steps.toLong())
        }
    }

    fun goToToday() {
        focus.value = clock.today()
    }

    /** From the month grid or a week's day header: that day's list. */
    fun openDay(date: LocalDate) {
        focus.value = date
        mode.value = TimetableMode.DAY
    }

    fun refreshCalendar() = plannerCalendar.refresh()

    fun cancel(item: TimetableItemUi) {
        val entryId = item.entryId ?: return
        viewModelScope.launch { timetableRepository.cancel(entryId, item.date) }
    }

    fun restore(item: TimetableItemUi) {
        val entryId = item.entryId ?: return
        viewModelScope.launch { timetableRepository.restoreDate(entryId, item.date) }
    }

    fun change(item: TimetableItemUi, start: LocalTime, end: LocalTime, room: String?) {
        val entryId = item.entryId ?: return
        viewModelScope.launch { timetableRepository.change(entryId, item.date, start, end, room) }
    }

    fun acceptSuggestion(item: TimetableItemUi) {
        val key = item.suggestionKey ?: return
        viewModelScope.launch { suggestionActions.accept(suggestions.value, key, clock.zone())?.let { events.send(it) } }
    }

    fun anotherTime(item: TimetableItemUi) {
        val key = item.suggestionKey ?: return
        viewModelScope.launch { events.send(suggestionActions.dismiss(key)) }
    }

    fun undo(event: UndoableEvent) {
        viewModelScope.launch { event.undo() }
    }

    fun copyTerm(onDone: (Int) -> Unit) {
        val from = state.value.copyFrom ?: return
        val to = state.value.copyTo ?: return
        viewModelScope.launch { onDone(timetableRepository.copyTerm(from, to)) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            TimetableViewModel(
                it.timetableRepository, it.taskRepository, it.moduleRepository, it.planRepository, it.plannerCalendar, it.dayRepository,
                it.suggestionService, SuggestionActions(it.suggestionRepository, it.dayRepository), it.clock,
            )
        }
    }
}
