package io.github.matteopi.budgeting.ui.planner.task

import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.core.planner.todo.QuickAdd
import io.github.matteopi.budgeting.core.planner.todo.TaskField
import io.github.matteopi.budgeting.core.planner.todo.TaskRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The task editor's fields (docs/PLAN.md §13.9). Pure, so the screen only draws it: [toTask] gives the task to save
 * and [errors] what stops it. What the editor doesn't show (done, created, order) is kept from the task it edits.
 */
data class TaskForm(
    val id: Long = 0,
    val title: String = "",
    val notes: String = "",
    val kind: TaskKind = TaskKind.TASK,
    val deadlineType: DeadlineType = DeadlineType.ASSIGNMENT,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val lengthMinutes: Int? = null,
    val estimateMinutes: Int? = null,
    val priority: Priority = Priority.NONE,
    val moduleId: Long? = null,
    val tagsText: String = "",
    val repeatKind: RepeatKind? = null,
    val repeatInterval: Int = 1,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val reminderMinutesBefore: Int? = null,
    val parentId: Long? = null,
    val location: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val completedAt: Instant? = null,
    val createdAt: Instant = Instant.EPOCH,
    val sortOrder: Int = 0,
) {
    val isStep: Boolean get() = parentId != null
    val isDeadline: Boolean get() = kind == TaskKind.DEADLINE
    val isExam: Boolean get() = isDeadline && deadlineType == DeadlineType.EXAM

    /** Only plain tasks repeat; a step or a deadline never does. */
    val canRepeat: Boolean get() = !isStep && !isDeadline

    fun toTask(): Task = Task(
        id = id,
        title = title.trim(),
        notes = notes.trim(),
        kind = if (isStep) TaskKind.TASK else kind,
        deadlineType = if (isDeadline && !isStep) deadlineType else null,
        dueDate = dueDate,
        dueTime = dueTime,
        lengthMinutes = if (isExam) lengthMinutes else null,
        estimateMinutes = estimateMinutes,
        priority = priority,
        moduleId = moduleId,
        tags = tagsText.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct(),
        repeat = repeatKind?.takeIf { canRepeat }?.let { TaskRepeat(it, repeatInterval, if (it == RepeatKind.WEEKDAYS) repeatDays else emptySet()) },
        reminderMinutesBefore = reminderMinutesBefore,
        parentId = parentId,
        location = location.trim().takeIf { it.isNotEmpty() },
        completedAt = completedAt,
        createdAt = createdAt,
        sortOrder = sortOrder,
        checklist = checklist.filter { it.text.isNotBlank() }.map { it.copy(text = it.text.trim()) },
    )

    val errors: Map<TaskField, String> get() = TaskRules.errors(toTask())

    /** The reminder choices for this task: by the time when it has one, else on the day. The current one is always offered. */
    val reminderChoices: List<Int?>
        get() {
            val base = if (dueTime != null) listOf(null, 0, 15, 60, 1440) else listOf(null, 0, 1440)
            return if (reminderMinutesBefore in base) base else base + reminderMinutesBefore
        }

    companion object {
        fun from(task: Task) = TaskForm(
            id = task.id,
            title = task.title,
            notes = task.notes,
            kind = task.kind,
            deadlineType = task.deadlineType ?: DeadlineType.ASSIGNMENT,
            dueDate = task.dueDate,
            dueTime = task.dueTime,
            lengthMinutes = task.lengthMinutes,
            estimateMinutes = task.estimateMinutes,
            priority = task.priority,
            moduleId = task.moduleId,
            tagsText = task.tags.joinToString(", "),
            repeatKind = task.repeat?.kind,
            repeatInterval = task.repeat?.interval ?: 1,
            repeatDays = task.repeat?.weekdays ?: emptySet(),
            reminderMinutesBefore = task.reminderMinutesBefore,
            parentId = task.parentId,
            location = task.location ?: "",
            checklist = task.checklist,
            completedAt = task.completedAt,
            createdAt = task.createdAt,
            sortOrder = task.sortOrder,
        )

        /** A new task from the To-do tab's quick-add line. */
        fun fromQuick(q: QuickAdd) = TaskForm(
            title = q.title,
            dueDate = q.dueDate,
            dueTime = q.dueTime,
            estimateMinutes = q.estimateMinutes,
            priority = q.priority,
            moduleId = q.moduleId,
            tagsText = q.tags.joinToString(", "),
        )
    }
}
