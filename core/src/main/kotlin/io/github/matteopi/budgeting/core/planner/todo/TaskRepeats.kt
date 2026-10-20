package io.github.matteopi.budgeting.core.planner.todo

import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** When a repeating task is due next (docs/PLAN.md §13.4). */
object TaskRepeats {

    /**
     * The next due date after completing the occurrence due on [due] on [completedOn]. It steps from [due]
     * by the rule until the date is after [completedOn], so a task done late doesn't come back for the days
     * already missed, and one done early moves on by one step only.
     */
    fun next(due: LocalDate, repeat: TaskRepeat, completedOn: LocalDate): LocalDate {
        val after = if (completedOn.isAfter(due)) completedOn else due
        return when (repeat.kind) {
            RepeatKind.DAYS, RepeatKind.WEEKS -> {
                val stepDays = repeat.interval.coerceAtLeast(1).toLong() * (if (repeat.kind == RepeatKind.WEEKS) 7 else 1)
                val gap = ChronoUnit.DAYS.between(due, after)
                due.plusDays((gap / stepDays + 1) * stepDays)
            }
            RepeatKind.WEEKDAYS -> {
                // An empty set can't be saved (TaskRules); a week on is the safe answer for one read from old data.
                if (repeat.weekdays.isEmpty()) return after.plusDays(7)
                var date = after.plusDays(1)
                while (date.dayOfWeek !in repeat.weekdays) date = date.plusDays(1)
                date
            }
        }
    }
}
