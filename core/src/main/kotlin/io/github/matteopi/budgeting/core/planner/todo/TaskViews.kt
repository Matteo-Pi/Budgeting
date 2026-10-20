package io.github.matteopi.budgeting.core.planner.todo

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Task
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The To-do tab's filters (docs/PLAN.md §12.3). */
enum class TodoFilter { TODAY, UPCOMING, OVERDUE, NO_DATE, BY_MODULE, DONE }

/** A titled group of tasks: a day, a week or a module. */
data class TaskGroup(val title: String, val tasks: List<Task>)

/** Groups and orders open tasks for the To-do tab and Today (docs/PLAN.md §13.4). */
object TaskViews {

    /** Timed tasks first by time, then by priority (high first), then the order they were added in. */
    val ORDER: Comparator<Task> = compareBy<Task>({ it.dueTime == null }, { it.dueTime })
        .thenByDescending { it.priority.ordinal }
        .thenBy { it.sortOrder }
        .thenBy { it.id }

    private fun open(tasks: List<Task>) = tasks.filter { it.isOpen }

    /** Open tasks due on [today]. */
    fun today(tasks: List<Task>, today: LocalDate): List<Task> =
        open(tasks).filter { it.dueDate == today }.sortedWith(ORDER)

    /** Open tasks due before [today], oldest first. */
    fun overdue(tasks: List<Task>, today: LocalDate): List<Task> =
        open(tasks).filter { it.dueDate != null && it.dueDate.isBefore(today) }
            .sortedWith(compareBy<Task> { it.dueDate }.then(ORDER))

    /** Open tasks without a date. */
    fun noDate(tasks: List<Task>): List<Task> = open(tasks).filter { it.dueDate == null }.sortedWith(ORDER)

    /**
     * Open tasks due after [today]: one group per day for the rest of this week and all of next ("Tomorrow",
     * "Friday", "Tue 27 Oct"), then one per week from the Monday after ("Week of 2 Nov").
     */
    fun upcoming(tasks: List<Task>, today: LocalDate): List<TaskGroup> {
        val later = open(tasks).filter { it.dueDate != null && it.dueDate.isAfter(today) }
        val lastDay = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).plusWeeks(1)
        val byDay = sortedMapOf<LocalDate, MutableList<Task>>()
        val byWeek = sortedMapOf<LocalDate, MutableList<Task>>()
        for (task in later) {
            val due = task.dueDate!!
            if (!due.isAfter(lastDay)) {
                byDay.getOrPut(due) { mutableListOf() } += task
            } else {
                byWeek.getOrPut(due.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { mutableListOf() } += task
            }
        }
        return byDay.map { (day, list) -> TaskGroup(PlannerText.day(day, today), list.sortedWith(ORDER)) } +
            byWeek.map { (monday, list) ->
                TaskGroup(
                    "Week of ${PlannerText.date(monday, today).substringAfter(' ')}",
                    list.sortedWith(compareBy<Task> { it.dueDate }.then(ORDER)),
                )
            }
    }

    /** Open tasks by module, in the modules' order, then "No module"; dated tasks first, by date. */
    fun byModule(tasks: List<Task>, modules: List<Module>): List<TaskGroup> {
        val order = compareBy<Task>({ it.dueDate == null }, { it.dueDate }).then(ORDER)
        val grouped = open(tasks).groupBy { it.moduleId }
        val known = modules.sortedWith(compareBy({ it.sortOrder }, { it.code.lowercase() }))
            .mapNotNull { module -> grouped[module.id]?.let { TaskGroup(module.label, it.sortedWith(order)) } }
        val knownIds = modules.map { it.id }.toSet()
        val rest = grouped.filterKeys { it == null || it !in knownIds }.values.flatten()
        return known + listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { TaskGroup("No module", it.sortedWith(order)) })
    }

    /** How many open tasks each filter would show, for the chips' counts. */
    fun counts(tasks: List<Task>, today: LocalDate): Map<TodoFilter, Int> = mapOf(
        TodoFilter.TODAY to today(tasks, today).size,
        TodoFilter.OVERDUE to overdue(tasks, today).size,
        TodoFilter.UPCOMING to open(tasks).count { it.dueDate != null && it.dueDate.isAfter(today) },
        TodoFilter.NO_DATE to noDate(tasks).size,
    )
}
