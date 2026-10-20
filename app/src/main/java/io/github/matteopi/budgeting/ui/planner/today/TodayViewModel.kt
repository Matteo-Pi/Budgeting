package io.github.matteopi.budgeting.ui.planner.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.core.planner.todo.TaskViews
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.CompletionUndo
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.planner.PlannerCalendar
import io.github.matteopi.budgeting.domain.planner.instances
import io.github.matteopi.budgeting.ui.planner.components.TaskRowUi
import io.github.matteopi.budgeting.ui.planner.timetable.ItemKind
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableItemUi
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableMapping
import io.github.matteopi.budgeting.ui.planner.todo.TodoMapping
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayUiState(
    val loading: Boolean = true,
    val today: LocalDate,
    /** "Today · Tue 20 Oct". */
    val title: String = "Today",
    val overdue: List<TaskRowUi> = emptyList(),
    val dueToday: List<TaskRowUi> = emptyList(),
    /** Today's classes (cancelled ones too, marked) and calendar events, by start. */
    val onToday: List<TimetableItemUi> = emptyList(),
)

/**
 * The Today tab (docs/PLAN.md §12.2): the day's classes and events, and the day's and overdue tasks. P3 adds the
 * day's timeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val taskRepository: TaskRepository,
    moduleRepository: ModuleRepository,
    timetableRepository: TimetableRepository,
    planRepository: PlanRepository,
    plannerCalendar: PlannerCalendar,
    clock: Clock,
) : ViewModel() {
    private val completions = Channel<CompletionUndo>(Channel.BUFFERED)

    /** A task just ticked off, for the *Undo* snackbar. */
    val completed: Flow<CompletionUndo> = completions.receiveAsFlow()

    private val calendar = clock.todayFlow.flatMapLatest { today ->
        val zone = clock.zone()
        plannerCalendar.observe(today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant())
    }

    private val timetable = combine(
        timetableRepository.observeEntries(),
        timetableRepository.observeExceptions(),
        planRepository.observeTermPeriods(),
    ) { entries, exceptions, terms -> Triple(entries, exceptions, terms) }

    val state: StateFlow<TodayUiState> = combine(
        taskRepository.observeOpenTasks(),
        moduleRepository.observeModules(),
        timetable,
        calendar,
        clock.todayFlow,
    ) { tasks, modules, (entries, exceptions, terms), cal, today ->
        val zone = clock.zone()
        val occurrences = Timetable.occurrences(entries, exceptions, terms, today, today, zone, includeCancelled = true)
        TodayUiState(
            loading = false,
            today = today,
            title = "Today · ${PlannerText.date(today, today)}",
            overdue = TodoMapping.rows(TaskViews.overdue(tasks, today), tasks, modules, today),
            dueToday = TodoMapping.rows(TaskViews.today(tasks, today), tasks, modules, today),
            // Deadlines and exams are already in the task lists.
            onToday = TimetableMapping.dayItems(today, occurrences, cal.instances, emptyList(), modules, zone)
                .filter { it.kind != ItemKind.TASK && it.kind != ItemKind.EXAM },
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState(today = clock.today()))

    fun complete(taskId: Long) {
        viewModelScope.launch {
            val task = taskRepository.getTask(taskId) ?: return@launch
            if (task.isOpen) completions.send(taskRepository.complete(task))
        }
    }

    fun undo(undo: CompletionUndo) {
        viewModelScope.launch { taskRepository.undoComplete(undo) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            TodayViewModel(it.taskRepository, it.moduleRepository, it.timetableRepository, it.planRepository, it.plannerCalendar, it.clock)
        }
    }
}
