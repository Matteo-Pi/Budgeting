package io.github.matteopi.budgeting.ui.planner.todo

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.todo.DeadlineState
import io.github.matteopi.budgeting.core.planner.todo.DeadlineStatus
import io.github.matteopi.budgeting.core.planner.todo.QuickAdd
import io.github.matteopi.budgeting.core.planner.todo.TaskGroup
import io.github.matteopi.budgeting.core.planner.todo.TaskViews
import io.github.matteopi.budgeting.core.planner.todo.TodoFilter
import io.github.matteopi.budgeting.data.repo.planner.DoneItem
import io.github.matteopi.budgeting.ui.planner.components.TaskRowUi
import java.time.LocalDate
import java.time.ZoneId

/** A titled list of task rows. */
@Immutable
data class TodoSection(val title: String, val rows: List<TaskRowUi>)

/** A row of the Done log: "Done today at 14:05 · EC101". */
@Immutable
data class DoneRowUi(val completionId: Long, val taskId: Long, val title: String, val subtitle: String)

/** The To-do tab's pure mapping (docs/PLAN.md §13.9), tested on the JVM. */
object TodoMapping {

    /** How far back the Done log goes. */
    const val DONE_DAYS = 30L

    val FILTERS: List<TodoFilter> = listOf(
        TodoFilter.TODAY, TodoFilter.UPCOMING, TodoFilter.OVERDUE, TodoFilter.NO_DATE, TodoFilter.BY_MODULE, TodoFilter.DONE,
    )

    fun filterLabel(filter: TodoFilter): String = when (filter) {
        // Not "Today": that is the name of the Planner's first tab.
        TodoFilter.TODAY -> "Due today"
        TodoFilter.UPCOMING -> "Upcoming"
        TodoFilter.OVERDUE -> "Overdue"
        TodoFilter.NO_DATE -> "No date"
        TodoFilter.BY_MODULE -> "By module"
        TodoFilter.DONE -> "Done"
    }

    fun emptyMessage(filter: TodoFilter): String = when (filter) {
        TodoFilter.TODAY -> "Nothing due today. Type a task above, such as \"Essay draft fri 3pm 2h\"."
        TodoFilter.UPCOMING -> "Nothing coming up."
        TodoFilter.OVERDUE -> "Nothing overdue."
        TodoFilter.NO_DATE -> "No tasks without a date."
        TodoFilter.BY_MODULE -> "No open tasks."
        TodoFilter.DONE -> "Nothing done in the last 30 days yet."
    }

    /** The sections of [filter] (all but [TodoFilter.DONE]) from the open [tasks]. */
    fun sections(filter: TodoFilter, tasks: List<Task>, modules: List<Module>, today: LocalDate): List<TodoSection> {
        val rows = RowBuilder(tasks, modules, today)
        fun section(title: String, list: List<Task>) = if (list.isEmpty()) null else TodoSection(title, list.map(rows::row))
        fun group(g: TaskGroup) = TodoSection(g.title, g.tasks.map(rows::row))
        return when (filter) {
            TodoFilter.TODAY -> listOfNotNull(
                section("Overdue", TaskViews.overdue(tasks, today)),
                section("Today", TaskViews.today(tasks, today)),
            )
            TodoFilter.UPCOMING -> TaskViews.upcoming(tasks, today).map(::group)
            TodoFilter.OVERDUE -> listOfNotNull(section("Overdue", TaskViews.overdue(tasks, today)))
            TodoFilter.NO_DATE -> listOfNotNull(section("No date", TaskViews.noDate(tasks)))
            TodoFilter.BY_MODULE -> TaskViews.byModule(tasks, modules).map(::group)
            TodoFilter.DONE -> emptyList()
        }
    }

    /** The row of one open task, for screens that show a single list (Today). */
    fun rows(list: List<Task>, allOpen: List<Task>, modules: List<Module>, today: LocalDate): List<TaskRowUi> {
        val rows = RowBuilder(allOpen, modules, today)
        return list.map(rows::row)
    }

    fun doneRow(item: DoneItem, modules: List<Module>, today: LocalDate, zone: ZoneId): DoneRowUi {
        val at = item.completion.completedAt.atZone(zone)
        val day = PlannerText.day(at.toLocalDate(), today).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }
        val module = item.moduleId?.let { id -> modules.firstOrNull { it.id == id }?.code }
        val subtitle = listOfNotNull("Done $day at ${PlannerText.time(at.toLocalTime())}", module).joinToString(" · ")
        return DoneRowUi(item.completion.id, item.completion.taskId, item.title, subtitle)
    }

    /** What the quick-add line was read as, for the chips under it. */
    fun previewChips(q: QuickAdd, modules: List<Module>, today: LocalDate): List<String> = listOfNotNull(
        q.dueDate?.let { PlannerText.date(it, today) },
        q.dueTime?.let(PlannerText::time),
        q.estimateMinutes?.let(PlannerText::duration),
        q.moduleId?.let { id -> modules.firstOrNull { it.id == id }?.code },
        q.priority.takeIf { it != Priority.NONE }?.let { priorityLabel(it) },
    ) + q.tags.map { "#$it" }

    fun priorityLabel(priority: Priority): String = when (priority) {
        Priority.NONE -> "No priority"
        Priority.LOW -> "Low priority"
        Priority.MEDIUM -> "Medium priority"
        Priority.HIGH -> "High priority"
    }

    /** The task a quick-add line saves as. */
    fun taskFrom(q: QuickAdd): Task = Task(
        title = q.title,
        dueDate = q.dueDate,
        dueTime = q.dueTime,
        estimateMinutes = q.estimateMinutes,
        moduleId = q.moduleId,
        tags = q.tags,
        priority = q.priority,
    )

    /** Builds rows with what they need of the other tasks: a step's deadline, and a deadline's open steps. */
    private class RowBuilder(allOpen: List<Task>, modules: List<Module>, private val today: LocalDate) {
        private val byId = allOpen.associateBy { it.id }
        private val stepsOf = allOpen.filter { it.parentId != null }.groupBy { it.parentId!! }
        private val modulesById = modules.associateBy { it.id }

        fun row(task: Task): TaskRowUi {
            val module = task.moduleId?.let { modulesById[it] }
            val parent = task.parentId?.let { byId[it] }
            val checklist = task.checklist.takeIf { it.isNotEmpty() }?.let { list -> "${list.count { it.done }}/${list.size} done" }
            val subtitle = listOfNotNull(
                parent?.let { "Step of ${it.title}" },
                task.dueDate?.let { PlannerText.due(it, task.dueTime, today) },
                module?.code,
                task.estimateMinutes?.takeIf { !task.isDeadline }?.let(PlannerText::duration),
                checklist,
                task.repeat?.let(PlannerText::repeat),
            ).plus(task.tags.map { "#$it" }).joinToString(" · ")
            return TaskRowUi(
                id = task.id,
                title = task.title,
                subtitle = subtitle,
                isDeadline = task.isDeadline,
                overdue = task.dueDate?.isBefore(today) == true,
                done = !task.isOpen,
                repeats = task.repeat != null,
                highPriority = task.priority == Priority.HIGH,
                moduleColor = module?.colorArgb,
                status = if (task.isDeadline) deadlineStatus(task, stepsOf[task.id].orEmpty()) else null,
            )
        }

        private fun deadlineStatus(deadline: Task, openSteps: List<Task>): String? {
            val progress = DeadlineStatus.of(deadline, openSteps, emptyMap(), today)
            if (progress.state == DeadlineState.DONE || progress.state == DeadlineState.OVERDUE) return null
            val due = deadline.dueDate ?: return null
            return listOfNotNull(
                progress.workLeftMinutes?.let { "${PlannerText.duration(it)} left" },
                openSteps.size.takeIf { it > 0 }?.let { if (it == 1) "1 step left" else "$it steps left" },
                "due ${PlannerText.countdown(due, today)}",
            ).joinToString(" · ")
        }
    }
}
