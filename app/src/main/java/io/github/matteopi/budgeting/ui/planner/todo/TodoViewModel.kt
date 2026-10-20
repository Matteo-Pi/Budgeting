package io.github.matteopi.budgeting.ui.planner.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.todo.TaskQuickAdd
import io.github.matteopi.budgeting.core.planner.todo.TaskViews
import io.github.matteopi.budgeting.core.planner.todo.TodoFilter
import io.github.matteopi.budgeting.data.repo.planner.CompletionUndo
import io.github.matteopi.budgeting.data.repo.planner.DeletedTask
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodoUiState(
    val loading: Boolean = true,
    val today: LocalDate,
    val filter: TodoFilter = TodoFilter.TODAY,
    val counts: Map<TodoFilter, Int> = emptyMap(),
    val sections: List<TodoSection> = emptyList(),
    val done: List<DoneRowUi> = emptyList(),
    val modules: List<Module> = emptyList(),
    val previewChips: List<String> = emptyList(),
)

/** What the screen says after an action, with *Undo* where it can be undone. */
sealed interface TodoEvent {
    val message: String

    data class Completed(val undo: CompletionUndo) : TodoEvent {
        override val message = "Done: ${undo.task.title}"
    }

    data class Deleted(val deleted: DeletedTask) : TodoEvent {
        override val message = "Deleted: ${deleted.task.title}"
    }

    data class Added(override val message: String) : TodoEvent

    /** The quick-add line has no title: the editor opens with it. */
    data class OpenEditor(val text: String) : TodoEvent {
        override val message = ""
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModel(
    private val taskRepository: TaskRepository,
    moduleRepository: ModuleRepository,
    private val clock: Clock,
) : ViewModel() {
    private val filter = MutableStateFlow(TodoFilter.TODAY)
    private val quickText = MutableStateFlow("")
    private val events = Channel<TodoEvent>(Channel.BUFFERED)

    /** One-off messages for the snackbar. */
    val eventFlow: Flow<TodoEvent> = events.receiveAsFlow()

    private data class Source(val tasks: List<Task>, val modules: List<Module>, val today: LocalDate)

    private val source = combine(taskRepository.observeOpenTasks(), moduleRepository.observeModules(), clock.todayFlow) { tasks, modules, today ->
        Source(tasks, modules, today)
    }

    private val doneLog = clock.todayFlow.flatMapLatest { taskRepository.observeDoneLog(it.minusDays(TodoMapping.DONE_DAYS)) }

    val state: StateFlow<TodoUiState> = combine(source, filter, quickText, doneLog) { src, filter, text, done ->
        val quick = TaskQuickAdd.parse(text, src.today, src.modules)
        TodoUiState(
            loading = false,
            today = src.today,
            filter = filter,
            counts = TaskViews.counts(src.tasks, src.today),
            sections = TodoMapping.sections(filter, src.tasks, src.modules, src.today),
            done = if (filter == TodoFilter.DONE) done.map { TodoMapping.doneRow(it, src.modules, src.today, clock.zone()) } else emptyList(),
            modules = src.modules,
            previewChips = if (text.isBlank()) emptyList() else TodoMapping.previewChips(quick, src.modules, src.today),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoUiState(today = clock.today()))

    fun setFilter(value: TodoFilter) {
        filter.value = value
    }

    fun setQuickText(value: String) {
        quickText.value = value
    }

    /** Saves the quick-add line as a task, or opens the editor with it when it has no title. */
    fun addQuick() {
        val text = quickText.value
        if (text.isBlank()) return
        viewModelScope.launch {
            val current = state.value
            val quick = TaskQuickAdd.parse(text, current.today, current.modules)
            if (quick.title.isBlank()) {
                events.send(TodoEvent.OpenEditor(text))
                return@launch
            }
            taskRepository.save(TodoMapping.taskFrom(quick))
            events.send(TodoEvent.Added("Added: ${quick.title}"))
        }
    }

    fun complete(taskId: Long) {
        viewModelScope.launch {
            val task = taskRepository.getTask(taskId) ?: return@launch
            if (!task.isOpen) return@launch
            events.send(TodoEvent.Completed(taskRepository.complete(task)))
        }
    }

    fun moveToTomorrow(taskId: Long) {
        viewModelScope.launch {
            val task = taskRepository.getTask(taskId) ?: return@launch
            taskRepository.moveTo(task, clock.today().plusDays(1))
        }
    }

    fun delete(taskId: Long) {
        viewModelScope.launch {
            taskRepository.delete(taskId)?.let { events.send(TodoEvent.Deleted(it)) }
        }
    }

    /** *Not done after all* on a Done log row. */
    fun markNotDone(row: DoneRowUi) {
        viewModelScope.launch { taskRepository.markNotDone(row.completionId, row.taskId) }
    }

    fun undo(event: TodoEvent) {
        viewModelScope.launch {
            when (event) {
                is TodoEvent.Completed -> taskRepository.undoComplete(event.undo)
                is TodoEvent.Deleted -> taskRepository.restore(event.deleted)
                is TodoEvent.Added, is TodoEvent.OpenEditor -> Unit
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            TodoViewModel(it.taskRepository, it.moduleRepository, it.clock)
        }
    }
}
