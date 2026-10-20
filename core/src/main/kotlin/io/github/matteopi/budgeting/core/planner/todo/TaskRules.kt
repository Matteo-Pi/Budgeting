package io.github.matteopi.budgeting.core.planner.todo

import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind

/** The fields of the task editor that can carry an error. */
enum class TaskField { TITLE, DUE_DATE, ESTIMATE, LENGTH, REPEAT, REMINDER }

/** What a task needs before it can be saved (docs/PLAN.md §13.4). The editor keeps *Save* off until there are none. */
object TaskRules {

    const val MIN_ESTIMATE_MINUTES = 5
    const val MAX_ESTIMATE_MINUTES = 200 * 60
    const val MAX_LENGTH_MINUTES = 12 * 60
    const val MAX_REPEAT_INTERVAL = 365

    fun errors(task: Task): Map<TaskField, String> {
        val errors = LinkedHashMap<TaskField, String>()
        if (task.title.isBlank()) errors[TaskField.TITLE] = "Enter a title."
        when {
            task.dueDate == null && task.kind == TaskKind.DEADLINE -> errors[TaskField.DUE_DATE] = "A deadline needs a date."
            task.dueDate == null && task.dueTime != null -> errors[TaskField.DUE_DATE] = "Choose a date for the time."
        }
        task.estimateMinutes?.let {
            if (it !in MIN_ESTIMATE_MINUTES..MAX_ESTIMATE_MINUTES) {
                errors[TaskField.ESTIMATE] = "An estimate is 5 minutes to 200 hours."
            }
        }
        task.lengthMinutes?.let {
            if (it !in MIN_ESTIMATE_MINUTES..MAX_LENGTH_MINUTES) errors[TaskField.LENGTH] = "A length is 5 minutes to 12 hours."
        }
        if (task.lengthMinutes != null && task.dueTime == null) errors[TaskField.LENGTH] = "Choose a start time for the length."
        task.repeat?.let { repeat ->
            when {
                task.dueDate == null -> errors[TaskField.REPEAT] = "A repeating task needs a first date."
                task.parentId != null || task.kind == TaskKind.DEADLINE -> errors[TaskField.REPEAT] = "Only plain tasks can repeat."
                repeat.kind == RepeatKind.WEEKDAYS && repeat.weekdays.isEmpty() -> errors[TaskField.REPEAT] = "Choose at least one day."
                repeat.kind != RepeatKind.WEEKDAYS && repeat.interval !in 1..MAX_REPEAT_INTERVAL ->
                    errors[TaskField.REPEAT] = "Repeat every 1 to 365."
            }
        }
        task.reminderMinutesBefore?.let {
            when {
                task.dueDate == null -> errors[TaskField.REMINDER] = "A reminder needs a date."
                it < 0 -> errors[TaskField.REMINDER] = "A reminder can't be after the task."
            }
        }
        if (task.deadlineType != null && task.kind != TaskKind.DEADLINE) {
            errors[TaskField.DUE_DATE] = errors[TaskField.DUE_DATE] ?: "Only deadlines have a type."
        }
        return errors
    }

    fun isValid(task: Task): Boolean = errors(task).isEmpty()

    /** True when [code] is free for a module other than [exceptId] (codes are compared without case). */
    fun moduleCodeFree(code: String, modules: List<Module>, exceptId: Long = 0): Boolean =
        modules.none { it.id != exceptId && it.code.trim().equals(code.trim(), ignoreCase = true) }

    /** An exam is a deadline of type [DeadlineType.EXAM]. */
    fun isExam(task: Task): Boolean = task.kind == TaskKind.DEADLINE && task.deadlineType == DeadlineType.EXAM
}
