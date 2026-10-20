package io.github.matteopi.budgeting.widget.planner

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.todo.TaskViews
import io.github.matteopi.budgeting.data.settings.TimerState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One of today's tasks on the widget, with a tick. */
data class WidgetTask(val id: Long, val title: String, val detail: String)

/**
 * What the Planner widget shows (docs/PLAN.md §13.7): the class on now or next, the free time left today, up to
 * [MAX_TASKS] of today's tasks, and the timer. Worked out by [PlannerWidgetState.of], which the JVM tests cover.
 */
data class PlannerWidgetState(
    /** "Now: EC101 Lecture · LT1" or "Next: EC101 Lecture at 14:00 · LT1", or null with no more classes today. */
    val classLine: String?,
    /** "2 h 30 m free left today". */
    val freeLine: String,
    val tasks: List<WidgetTask>,
    /** "Study · EC101 · 35 m" while the timer runs. */
    val timerLine: String?,
    /** The category the timer's start chip records: study, if there is one. */
    val startCategoryId: Long?,
) {
    companion object {
        const val MAX_TASKS = 3

        fun of(
            now: Instant,
            today: LocalDate,
            zone: ZoneId,
            occurrences: List<Occurrence>,
            freeLeftMinutes: Long,
            tasks: List<Task>,
            categories: List<TimeCategory>,
            modules: List<Module>,
            timer: TimerState?,
        ): PlannerWidgetState {
            val next = occurrences.filter { !it.cancelled && it.end.isAfter(now) && it.date == today }.minByOrNull { it.start }
            val classLine = next?.let { o ->
                val where = o.room.ifBlank { o.location }
                val place = if (where.isBlank()) "" else " · $where"
                if (o.start.isAfter(now)) "Next: ${o.title} at ${PlannerText.time(o.startTime)}$place" else "Now: ${o.title}$place"
            }
            val shown = (TaskViews.overdue(tasks, today) + TaskViews.today(tasks, today)).take(MAX_TASKS).map { t ->
                WidgetTask(t.id, t.title, PlannerText.due(t.dueDate ?: today, t.dueTime, today))
            }
            val timerLine = timer?.let { state ->
                val category = categories.firstOrNull { it.id == state.categoryId }?.name ?: "Timer"
                val module = state.moduleId?.let { id -> modules.firstOrNull { it.id == id }?.code }
                val minutes = Duration.between(state.start, now).toMinutes().coerceAtLeast(0)
                listOfNotNull(category, module, PlannerText.duration(minutes.toInt())).joinToString(" · ")
            }
            return PlannerWidgetState(
                classLine = classLine,
                freeLine = "${PlannerText.duration(freeLeftMinutes.toInt())} free left today",
                tasks = shown,
                timerLine = timerLine,
                startCategoryId = (categories.firstOrNull { it.systemKey == CategoryKey.STUDY && !it.archived } ?: categories.firstOrNull { !it.archived })?.id,
            )
        }
    }
}
