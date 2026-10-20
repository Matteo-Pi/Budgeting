package io.github.matteopi.budgeting.ui.planner.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.todo.TaskViews
import io.github.matteopi.budgeting.data.repo.planner.CompletionUndo
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.planner.components.TaskRowUi
import io.github.matteopi.budgeting.ui.planner.todo.TodoMapping
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
)

/** The Today tab (docs/PLAN.md §12.2). Stage P1 shows the day's and overdue tasks; P3 adds the day's timeline. */
class TodayViewModel(
    private val taskRepository: TaskRepository,
    moduleRepository: ModuleRepository,
    clock: Clock,
) : ViewModel() {
    private val completions = Channel<CompletionUndo>(Channel.BUFFERED)

    /** A task just ticked off, for the *Undo* snackbar. */
    val completed: Flow<CompletionUndo> = completions.receiveAsFlow()

    val state: StateFlow<TodayUiState> = combine(
        taskRepository.observeOpenTasks(),
        moduleRepository.observeModules(),
        clock.todayFlow,
    ) { tasks, modules, today ->
        TodayUiState(
            loading = false,
            today = today,
            title = "Today · ${PlannerText.date(today, today)}",
            overdue = TodoMapping.rows(TaskViews.overdue(tasks, today), tasks, modules, today),
            dueToday = TodoMapping.rows(TaskViews.today(tasks, today), tasks, modules, today),
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
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory { TodayViewModel(it.taskRepository, it.moduleRepository, it.clock) }
    }
}
