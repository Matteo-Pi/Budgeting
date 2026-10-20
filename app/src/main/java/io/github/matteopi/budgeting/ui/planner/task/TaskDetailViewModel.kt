package io.github.matteopi.budgeting.ui.planner.task

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplates
import io.github.matteopi.budgeting.core.planner.todo.DeadlineState
import io.github.matteopi.budgeting.core.planner.todo.DeadlineStatus
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.planner.components.TaskRowUi
import io.github.matteopi.budgeting.ui.planner.todo.TodoMapping
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A deadline's numbers in words (docs/PLAN.md §12.3). */
@Immutable
data class DeadlineCardUi(val headline: String, val lines: List<String>)

data class TaskDetailUiState(
    val loading: Boolean = true,
    /** Null once loaded means the task is gone (deleted elsewhere). */
    val task: Task? = null,
    val today: LocalDate,
    val details: List<String> = emptyList(),
    val parentTitle: String? = null,
    val deadline: DeadlineCardUi? = null,
    val steps: List<TaskRowUi> = emptyList(),
    val templates: List<BreakdownTemplate> = emptyList(),
)

/** One task's page: its details, checklist, and for a deadline its status and steps (docs/PLAN.md §13.9). */
class TaskDetailViewModel(
    private val taskId: Long,
    private val taskRepository: TaskRepository,
    moduleRepository: ModuleRepository,
    settingsStore: SettingsStore,
    private val clock: Clock,
) : ViewModel() {

    private val parts = combine(
        taskRepository.observeTask(taskId),
        taskRepository.observeSteps(taskId),
        moduleRepository.observeModules(),
        settingsStore.breakdownTemplates,
        clock.todayFlow,
    ) { task, steps, modules, templates, today -> Parts(task, steps, modules, templates, today) }

    private data class Parts(
        val task: Task?,
        val steps: List<Task>,
        val modules: List<Module>,
        val templates: List<BreakdownTemplate>,
        val today: LocalDate,
    )

    val state: StateFlow<TaskDetailUiState> = parts.map { p ->
        val task = p.task ?: return@map TaskDetailUiState(loading = false, task = null, today = p.today)
        val parent = task.parentId?.let { id -> taskRepository.getTask(id) }
        TaskDetailUiState(
            loading = false,
            task = task,
            today = p.today,
            details = details(task, p.modules, p.today),
            parentTitle = parent?.title,
            deadline = if (task.isDeadline) deadlineCard(task, p.steps, p.today) else null,
            steps = TodoMapping.rows(p.steps, p.steps, p.modules, p.today),
            templates = p.templates,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState(today = clock.today()))

    fun setChecklistItem(position: Int, done: Boolean) {
        viewModelScope.launch { taskRepository.setChecklistItem(taskId, position, done) }
    }

    fun complete() {
        val task = state.value.task ?: return
        viewModelScope.launch { if (task.isOpen) taskRepository.complete(task) else taskRepository.reopen(task) }
    }

    fun setStepDone(stepId: Long, done: Boolean) {
        viewModelScope.launch {
            val step = taskRepository.getTask(stepId) ?: return@launch
            if (done && step.isOpen) taskRepository.complete(step) else if (!done && !step.isOpen) taskRepository.reopen(step)
        }
    }

    /** *Add steps from a template*: the template's steps, dated up to the deadline. */
    fun applyTemplate(template: BreakdownTemplate) {
        val task = state.value.task ?: return
        viewModelScope.launch { taskRepository.addSteps(BreakdownTemplates.apply(template, task, clock.today())) }
    }

    private fun details(task: Task, modules: List<Module>, today: LocalDate): List<String> = listOfNotNull(
        task.dueDate?.let { PlannerText.due(it, task.dueTime, today) + " (${PlannerText.date(it, today)})" },
        task.lengthMinutes?.let { "Lasts ${PlannerText.duration(it)}" },
        task.repeat?.let { "Repeats: ${PlannerText.repeat(it)}" },
        task.estimateMinutes?.takeIf { !task.isDeadline }?.let { "Estimate: ${PlannerText.duration(it)}" },
        task.moduleId?.let { id -> modules.firstOrNull { it.id == id }?.label },
        task.location?.let { if (task.isDeadline) "Room: $it" else "Where: $it" },
        task.reminderMinutesBefore?.let { "Reminder: ${PlannerText.reminder(it, task.dueTime != null)}" },
        task.priority.takeIf { it != Priority.NONE }?.let(TodoMapping::priorityLabel),
        task.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
        task.completedAt?.let { "Done ${PlannerText.date(it.atZone(clock.zone()).toLocalDate(), today)}" },
    )

    private fun deadlineCard(task: Task, steps: List<Task>, today: LocalDate): DeadlineCardUi {
        val progress = DeadlineStatus.of(task, steps, emptyMap(), today)
        val due = task.dueDate ?: today
        val headline = when (progress.state) {
            DeadlineState.DONE -> "Done"
            DeadlineState.OVERDUE -> PlannerText.due(due, task.dueTime, today)
            else -> "Due ${PlannerText.countdown(due, today)}"
        }
        val lines = listOfNotNull(
            progress.workLeftMinutes?.let { left -> "${PlannerText.duration(left)} of work left" + (progress.estimateMinutes?.let { " of ${PlannerText.duration(it)}" } ?: "") },
            if (progress.state == DeadlineState.NO_ESTIMATE) "Add an estimate to see how much work is left." else null,
            progress.stepsTotal.takeIf { it > 0 }?.let { "${progress.stepsDone} of $it steps done" },
            if (progress.state == DeadlineState.IN_PROGRESS) "Whether it's on track comes with the timetable and your free time in a later build." else null,
        )
        return DeadlineCardUi(headline, lines)
    }

    companion object {
        fun factory(taskId: Long): ViewModelProvider.Factory = budgetViewModelFactory {
            TaskDetailViewModel(taskId, it.taskRepository, it.moduleRepository, it.settingsStore, it.clock)
        }
    }
}
