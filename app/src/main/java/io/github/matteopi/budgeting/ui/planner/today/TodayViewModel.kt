package io.github.matteopi.budgeting.ui.planner.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.todo.TaskViews
import io.github.matteopi.budgeting.data.repo.planner.CompletionUndo
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.settings.TimerState
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.planner.HabitService
import io.github.matteopi.budgeting.domain.planner.HabitsModel
import io.github.matteopi.budgeting.domain.planner.PlannerService
import io.github.matteopi.budgeting.domain.planner.PlannerTimer
import io.github.matteopi.budgeting.domain.planner.SuggestionService
import io.github.matteopi.budgeting.domain.planner.SuggestionsModel
import io.github.matteopi.budgeting.domain.planner.TimerStop
import io.github.matteopi.budgeting.ui.planner.components.TaskRowUi
import io.github.matteopi.budgeting.ui.planner.components.UndoableEvent
import io.github.matteopi.budgeting.ui.planner.day.DayEvent
import io.github.matteopi.budgeting.ui.planner.day.DayMapping
import io.github.matteopi.budgeting.ui.planner.habits.HabitActions
import io.github.matteopi.budgeting.ui.planner.habits.HabitRowUi
import io.github.matteopi.budgeting.ui.planner.habits.HabitsMapping
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionActions
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionRowUi
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionsMapping
import io.github.matteopi.budgeting.ui.planner.todo.TodoMapping
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import io.github.matteopi.budgeting.widget.planner.PlannerWidgetState
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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

data class TodayUiState(
    val loading: Boolean = true,
    /** The day the record is on (after midnight, a late night is still yesterday's). */
    val day: LocalDate,
    /** "Today · Tue 20 Oct". */
    val title: String = "Today",
    val overdue: List<TaskRowUi> = emptyList(),
    val dueToday: List<TaskRowUi> = emptyList(),
    /** The habits that count today. */
    val habits: List<HabitRowUi> = emptyList(),
    /** The habits' day, which they are ticked on. */
    val habitDay: LocalDate = day,
    /** Today's study suggestions (docs/PLAN.md §12.7). */
    val suggestions: List<SuggestionRowUi> = emptyList(),
    /** The day as an agenda: classes, events, plans, records and gaps. */
    val agenda: List<AgendaRowUi> = emptyList(),
    /** The Now card's lines: the class on now or next, the free time left, the timer. */
    val now: PlannerWidgetState? = null,
    /** "3 h 20 m free (1 h planned)". */
    val freeSummary: String = "",
    val finishYesterday: Boolean = false,
    val timerRunning: Boolean = false,
    val categories: List<TimeCategory> = emptyList(),
    val modules: List<Module> = emptyList(),
)

private data class TodayExtras(
    val timer: TimerState?,
    val today: LocalDate,
    val habits: HabitsModel,
    val suggestions: SuggestionsModel?,
)

/**
 * The Today tab (docs/PLAN.md §12.2, §13.9): the Now card, *Finish yesterday*, the day as an agenda (the same
 * timeline as the day record), today's and overdue tasks, and the timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val taskRepository: TaskRepository,
    moduleRepository: ModuleRepository,
    private val dayRepository: DayRepository,
    plannerService: PlannerService,
    habitService: HabitService,
    private val habitActions: HabitActions,
    suggestionService: SuggestionService,
    private val suggestionActions: SuggestionActions,
    private val plannerTimer: PlannerTimer,
    private val clock: Clock,
) : ViewModel() {
    private val completions = Channel<CompletionUndo>(Channel.BUFFERED)
    private val habitEvents = Channel<UndoableEvent>(Channel.BUFFERED)

    /** What a change to a habit's day did, for the *Undo* snackbar. */
    val habitEventFlow: Flow<UndoableEvent> = habitEvents.receiveAsFlow()
    private val timerEvents = Channel<DayEvent>(Channel.BUFFERED)

    /** A task just ticked off, for the *Undo* snackbar. */
    val completed: Flow<CompletionUndo> = completions.receiveAsFlow()

    /** What the timer did when stopped, for the snackbar. */
    val timerEventFlow: Flow<DayEvent> = timerEvents.receiveAsFlow()

    /** The latest suggestions, for *Accept*; null until the first run. */
    private val suggestionModel: StateFlow<SuggestionsModel?> = suggestionService.observe()
        .map<SuggestionsModel, SuggestionsModel?> { it }
        .onStart { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<TodayUiState> = plannerService.currentDay.flatMapLatest { day ->
        combine(
            plannerService.dayFlow(day),
            plannerService.dayFlow(day.minusDays(1)),
            taskRepository.observeOpenTasks(),
            moduleRepository.observeModules(),
            combine(plannerTimer.state, clock.todayFlow, habitService.observe(), suggestionModel) { timer, today, habits, suggestions ->
                TodayExtras(timer, today, habits, suggestions)
            },
        ) { model, yesterday, tasks, modules, (timer, today, habits, suggestions) ->
            TodayUiState(
                loading = false,
                day = day,
                title = "Today · ${PlannerText.date(day, day)}",
                overdue = TodoMapping.rows(TaskViews.overdue(tasks, today), tasks, modules, today),
                dueToday = TodoMapping.rows(TaskViews.today(tasks, today), tasks, modules, today),
                habits = HabitsMapping.forToday(habits),
                habitDay = habits.today,
                suggestions = suggestions?.let { SuggestionsMapping.within(it.suggestions, model.waking, habits.today, model.zone) } ?: emptyList(),
                agenda = TodayMapping.agenda(model, modules),
                now = PlannerWidgetState.of(model.now, day, model.zone, model.occurrences, model.freeLeftMinutes, tasks, model.categories, modules, timer),
                freeSummary = TodayMapping.freeSummary(model),
                finishYesterday = TodayMapping.offerFinishYesterday(yesterday),
                timerRunning = timer != null,
                categories = model.categories.filter { !it.archived },
                modules = modules.filter { !it.archived },
            )
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState(day = clock.today()))

    fun complete(taskId: Long) {
        viewModelScope.launch {
            val task = taskRepository.getTask(taskId) ?: return@launch
            if (task.isOpen) completions.send(taskRepository.complete(task))
        }
    }

    fun undo(undo: CompletionUndo) {
        viewModelScope.launch { taskRepository.undoComplete(undo) }
    }

    fun toggleHabit(habitId: Long) = habitChange { habitActions.toggle(habitId, it) }

    fun addToHabit(habitId: Long, amount: Int) = habitChange { habitActions.add(habitId, it, amount) }

    fun skipHabit(habitId: Long, skipped: Boolean) = habitChange { habitActions.skip(habitId, it, skipped) }

    fun undoHabit(event: UndoableEvent) {
        viewModelScope.launch { event.undo() }
    }

    private fun habitChange(change: suspend (LocalDate) -> UndoableEvent?) {
        viewModelScope.launch { change(state.value.habitDay)?.let { habitEvents.send(it) } }
    }

    fun acceptSuggestion(key: String) {
        viewModelScope.launch { suggestionActions.accept(suggestionModel.value, key, clock.zone())?.let { habitEvents.send(it) } }
    }

    fun anotherTime(key: String) {
        viewModelScope.launch { habitEvents.send(suggestionActions.dismiss(key)) }
    }

    fun startTimer(categoryId: Long, moduleId: Long?) {
        viewModelScope.launch { plannerTimer.start(categoryId, moduleId)?.let { timerEvents.send(eventOf(it)) } }
    }

    fun stopTimer() {
        viewModelScope.launch { plannerTimer.stop()?.let { timerEvents.send(eventOf(it)) } }
    }

    fun undoTimer(event: DayEvent) {
        val undo = event.undo ?: return
        viewModelScope.launch { undo() }
    }

    private fun eventOf(stop: TimerStop): DayEvent = when (stop) {
        is TimerStop.TooShort -> DayEvent("Under 5 minutes, so nothing was recorded")
        is TimerStop.Recorded -> {
            val minutes = Duration.between(stop.chunk.start, stop.chunk.end).toMinutes().toInt()
            val name = state.value.categories.firstOrNull { it.id == stop.chunk.categoryId }?.name ?: "time"
            DayEvent("Recorded ${PlannerText.duration(minutes)} of $name (${DayMapping.range(stop.chunk.start, stop.chunk.end, clock.zone())})") {
                dayRepository.applyRecords(stop.plan.inverse())
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            TodayViewModel(
                it.taskRepository, it.moduleRepository, it.dayRepository, it.plannerService, it.habitService, HabitActions(it.habitRepository),
                it.suggestionService, SuggestionActions(it.suggestionRepository, it.dayRepository), it.plannerTimer, it.clock,
            )
        }
    }
}
