package io.github.matteopi.budgeting.ui.planner.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.todo.TaskQuickAdd
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskEditUiState(
    val loading: Boolean = true,
    val form: TaskForm = TaskForm(),
    val modules: List<Module> = emptyList(),
    /** The deadline a step belongs to, for the title bar. */
    val parentTitle: String? = null,
    val today: LocalDate,
    /** Set once saved: the task's id. */
    val savedId: Long? = null,
    val deleted: Boolean = false,
    val confirmDelete: Boolean = false,
) {
    val isNew: Boolean get() = form.id == 0L
}

/** Adds or edits one task, deadline or step (docs/PLAN.md §13.9). */
class TaskEditViewModel(
    private val taskId: Long?,
    private val parentId: Long?,
    kind: String?,
    text: String?,
    private val taskRepository: TaskRepository,
    moduleRepository: ModuleRepository,
    clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskEditUiState(today = clock.today()))
    val state: StateFlow<TaskEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            moduleRepository.observeModules().collect { modules -> _state.update { it.copy(modules = modules) } }
        }
        viewModelScope.launch {
            val modules = moduleRepository.getModules()
            val form = when {
                taskId != null -> taskRepository.getTask(taskId)?.let(TaskForm::from) ?: TaskForm()
                parentId != null -> {
                    val parent = taskRepository.getTask(parentId)
                    TaskForm(parentId = parentId, moduleId = parent?.moduleId, dueDate = parent?.dueDate)
                }
                !text.isNullOrBlank() -> TaskForm.fromQuick(TaskQuickAdd.parse(text, clock.today(), modules))
                kind == TaskKind.DEADLINE.name -> TaskForm(kind = TaskKind.DEADLINE)
                else -> TaskForm()
            }
            val parentTitle = form.parentId?.let { taskRepository.getTask(it)?.title }
            _state.update { it.copy(loading = false, form = form, modules = modules, parentTitle = parentTitle) }
        }
    }

    fun update(change: (TaskForm) -> TaskForm) = _state.update { it.copy(form = change(it.form)) }

    fun save() {
        val current = _state.value
        if (current.loading || current.form.errors.isNotEmpty()) return
        viewModelScope.launch {
            val id = taskRepository.save(current.form.toTask())
            _state.update { it.copy(savedId = id) }
        }
    }

    fun requestDelete() = _state.update { it.copy(confirmDelete = true) }

    fun dismissDelete() = _state.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        val id = _state.value.form.id.takeIf { it != 0L } ?: return
        viewModelScope.launch {
            taskRepository.delete(id)
            _state.update { it.copy(deleted = true, confirmDelete = false) }
        }
    }

    companion object {
        fun factory(taskId: Long?, parentId: Long?, kind: String?, text: String?): ViewModelProvider.Factory =
            budgetViewModelFactory {
                TaskEditViewModel(taskId, parentId, kind, text, it.taskRepository, it.moduleRepository, it.clock)
            }
    }
}
