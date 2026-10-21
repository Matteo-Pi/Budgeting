package io.github.matteopi.budgeting.ui.planner.task

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RevisionTopic
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplates
import io.github.matteopi.budgeting.core.planner.todo.DeadlineProgress
import io.github.matteopi.budgeting.core.planner.todo.DeadlineState
import io.github.matteopi.budgeting.core.planner.todo.DeadlineStatus
import io.github.matteopi.budgeting.core.planner.todo.TaskRules
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.SuggestionRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.planner.PlannerService
import io.github.matteopi.budgeting.ui.planner.components.TaskRowUi
import io.github.matteopi.budgeting.ui.planner.todo.TodoMapping
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** An exam's revision topics (docs/PLAN.md §12.7). */
    val topics: List<RevisionTopic> = emptyList(),
)

/** One task's page: its details, checklist, and for a deadline its status and steps (docs/PLAN.md §13.9). */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModel(
    private val taskId: Long,
    private val taskRepository: TaskRepository,
    moduleRepository: ModuleRepository,
    settingsStore: SettingsStore,
    dayRepository: DayRepository,
    plannerService: PlannerService,
    private val suggestionRepository: SuggestionRepository,
    private val clock: Clock,
) : ViewModel() {

    private val task = taskRepository.observeTask(taskId)

    /** The free time before an open deadline's due date, for its on-track test; null for anything else. */
    private val freeBefore: Flow<Int?> = task.map { t -> t?.takeIf { it.isDeadline && it.isOpen }?.dueDate }.distinctUntilChanged()
        .flatMapLatest { due -> if (due == null) flowOf(null) else plannerService.freeMinutesBefore(due) }

    private val parts = combine(
        combine(task, taskRepository.observeSteps(taskId), moduleRepository.observeModules()) { t, steps, modules -> Triple(t, steps, modules) },
        settingsStore.breakdownTemplates,
        clock.todayFlow,
        dayRepository.observeMinutesByTask(),
        freeBefore,
    ) { (task, steps, modules), templates, today, studied, free -> Parts(task, steps, modules, templates, today, studied, free) }

    private data class Parts(
        val task: Task?,
        val steps: List<Task>,
        val modules: List<Module>,
        val templates: List<BreakdownTemplate>,
        val today: LocalDate,
        val studied: Map<Long, Int>,
        val freeBefore: Int?,
    )

    private val topics = suggestionRepository.observeTopics().map { list -> list.filter { it.examTaskId == taskId } }

    val state: StateFlow<TaskDetailUiState> = combine(parts, topics) { p, examTopics ->
        val task = p.task ?: return@combine TaskDetailUiState(loading = false, task = null, today = p.today)
        val parent = task.parentId?.let { id -> taskRepository.getTask(id) }
        TaskDetailUiState(
            loading = false,
            task = task,
            today = p.today,
            details = details(task, p.modules, p.today),
            parentTitle = parent?.title,
            deadline = if (task.isDeadline) deadlineCard(task, p.steps, p.today, p.studied, p.freeBefore) else null,
            steps = TodoMapping.rows(p.steps, p.steps, p.modules, p.today, p.studied),
            templates = p.templates,
            topics = if (TaskRules.isExam(task)) examTopics else emptyList(),
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

    /** Adds a revision topic to this exam, or saves one. */
    fun saveTopic(topic: RevisionTopic) {
        if (topic.name.isBlank()) return
        viewModelScope.launch { suggestionRepository.saveTopic(topic.copy(examTaskId = taskId)) }
    }

    fun deleteTopic(topicId: Long) {
        viewModelScope.launch { suggestionRepository.deleteTopic(topicId) }
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

    private fun deadlineCard(task: Task, steps: List<Task>, today: LocalDate, studied: Map<Long, Int>, freeBefore: Int?): DeadlineCardUi {
        val progress = DeadlineStatus.of(task, steps, studied, today, freeBefore)
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
            progress.studiedMinutes.takeIf { it > 0 }?.let { "${PlannerText.duration(it)} recorded on it" },
            onTrackLine(progress, due, today),
        )
        return DeadlineCardUi(headline, lines)
    }

    /**
     * "On track: 6 h left · 14 h free before Thu", with the reason for the half: other work and rest need time too
     * (docs/PLAN.md §13.4).
     */
    private fun onTrackLine(progress: DeadlineProgress, due: LocalDate, today: LocalDate): String? {
        val left = progress.workLeftMinutes ?: return null
        val free = progress.freeMinutes ?: return null
        val target = maxOf(today, due.minusDays(PlannerService.FINISH_EARLY_DAYS))
        val numbers = "${PlannerText.duration(left)} left · ${PlannerText.duration(free)} free to ${PlannerText.day(target, today)}"
        return when (progress.state) {
            DeadlineState.ON_TRACK -> "On track: $numbers"
            DeadlineState.TIGHT -> "Tight: $numbers. On track means the work takes at most half the free time, leaving the rest for other work and rest."
            DeadlineState.AT_RISK -> "At risk: $numbers. Plan time for it, or move other things."
            else -> null
        }
    }

    companion object {
        fun factory(taskId: Long): ViewModelProvider.Factory = budgetViewModelFactory {
            TaskDetailViewModel(
                taskId, it.taskRepository, it.moduleRepository, it.settingsStore, it.dayRepository, it.plannerService, it.suggestionRepository, it.clock,
            )
        }
    }
}
