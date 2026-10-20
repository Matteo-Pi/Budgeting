package io.github.matteopi.budgeting.core.planner.todo

import io.github.matteopi.budgeting.core.planner.model.Task
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Where a deadline stands. [IN_PROGRESS] is used until free time is known (docs/PLAN.md §13.10, stage P3 adds
 * [ON_TRACK], [TIGHT] and [AT_RISK]).
 */
enum class DeadlineState { DONE, OVERDUE, NO_ESTIMATE, IN_PROGRESS, ON_TRACK, TIGHT, AT_RISK }

/**
 * A deadline's numbers: [workLeftMinutes] (null without an estimate), [estimateMinutes] (the deadline's own, or its
 * steps' sum), [studiedMinutes] (recorded against it and its steps), [daysLeft] until it is due (negative when
 * overdue), the steps done and in all, and [freeMinutes] before the target date when known.
 */
data class DeadlineProgress(
    val state: DeadlineState,
    val workLeftMinutes: Int?,
    val estimateMinutes: Int?,
    val studiedMinutes: Int,
    val daysLeft: Long,
    val stepsDone: Int,
    val stepsTotal: Int,
    val freeMinutes: Int? = null,
)

/** Work left and the on-track test for deadlines (docs/PLAN.md §13.4). */
object DeadlineStatus {

    /** Work left at most this share of the free time is on track: the rest is for other work and rest. */
    const val ON_TRACK_SHARE = 0.5

    /**
     * [steps] are the deadline's steps; [studiedByTask] the minutes recorded per task id; [freeMinutesBefore] the
     * unplanned free time from now to the target date, or null while it isn't known.
     */
    fun of(
        deadline: Task,
        steps: List<Task>,
        studiedByTask: Map<Long, Int>,
        today: LocalDate,
        freeMinutesBefore: Int? = null,
    ): DeadlineProgress {
        fun studied(task: Task) = studiedByTask[task.id] ?: 0
        val studiedAll = studied(deadline) + steps.sumOf { studied(it) }
        val estimatedSteps = steps.filter { it.estimateMinutes != null }
        val (estimate, workLeft) = when {
            estimatedSteps.isNotEmpty() -> {
                val total = estimatedSteps.sumOf { it.estimateMinutes!! }
                val left = estimatedSteps.filter { it.isOpen }.sumOf { (it.estimateMinutes!! - studied(it)).coerceAtLeast(0) }
                total to left
            }
            deadline.estimateMinutes != null ->
                deadline.estimateMinutes to (deadline.estimateMinutes - studiedAll).coerceAtLeast(0)
            else -> null to null
        }
        val due = deadline.dueDate ?: today
        val daysLeft = ChronoUnit.DAYS.between(today, due)
        val state = when {
            !deadline.isOpen -> DeadlineState.DONE
            due.isBefore(today) -> DeadlineState.OVERDUE
            workLeft == null -> DeadlineState.NO_ESTIMATE
            freeMinutesBefore == null -> DeadlineState.IN_PROGRESS
            workLeft <= freeMinutesBefore * ON_TRACK_SHARE -> DeadlineState.ON_TRACK
            workLeft <= freeMinutesBefore -> DeadlineState.TIGHT
            else -> DeadlineState.AT_RISK
        }
        return DeadlineProgress(
            state = state,
            workLeftMinutes = workLeft,
            estimateMinutes = estimate,
            studiedMinutes = studiedAll,
            daysLeft = daysLeft,
            stepsDone = steps.count { !it.isOpen },
            stepsTotal = steps.size,
            freeMinutes = freeMinutesBefore,
        )
    }
}
