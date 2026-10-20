package io.github.matteopi.budgeting.core.planner.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/*
 * The Planner section's models (docs/PLAN.md §12, §13.3). Plain immutable values: ids are 0 until saved,
 * moments are Instants, dates LocalDates and times of day LocalTimes. Enums are stored by name, so never
 * rename an entry.
 */

/** A university module ("EC101 Principles of Economics"), with an optional weekly study target. */
data class Module(
    val id: Long = 0,
    val code: String,
    val name: String = "",
    val colorArgb: Long? = null,
    val weeklyTargetMinutes: Int? = null,
    /** How much it counts or how hard it is, 1 to 3: revision gives heavier modules more time. */
    val weight: Int = 2,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
) {
    /** "EC101 Principles of Economics", or the code alone. */
    val label: String get() = if (name.isBlank()) code else "$code $name"
}

enum class TaskKind { TASK, DEADLINE }

enum class DeadlineType { ASSIGNMENT, EXAM, APPLICATION, OTHER }

enum class Priority { NONE, LOW, MEDIUM, HIGH }

/** [DAYS]: every [TaskRepeat.interval] days; [WEEKS]: every [TaskRepeat.interval] weeks; [WEEKDAYS]: on the chosen days. */
enum class RepeatKind { DAYS, WEEKS, WEEKDAYS }

data class TaskRepeat(
    val kind: RepeatKind,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
)

/** One line of a task's checklist, in order. */
data class ChecklistItem(val text: String, val done: Boolean = false)

/**
 * A to-do item or a deadline (docs/PLAN.md §12.3).
 *
 * - [dueTime] needs [dueDate]; a deadline always has a date.
 * - [lengthMinutes] is an exam's length; [estimateMinutes] the work it needs.
 * - [reminderMinutesBefore] is counted back from the due moment: [dueTime] on the due date, or the default
 *   reminder time for a task without a time. So 0 is "at the time" (or "on the day"), and 1440 a day before.
 * - [parentId] makes this a step of that deadline.
 * - A repeating task never completes: completing it logs a [TaskCompletion] and moves [dueDate] on, so
 *   [completedAt] stays null.
 */
data class Task(
    val id: Long = 0,
    val title: String,
    val notes: String = "",
    val kind: TaskKind = TaskKind.TASK,
    val deadlineType: DeadlineType? = null,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val lengthMinutes: Int? = null,
    val estimateMinutes: Int? = null,
    val priority: Priority = Priority.NONE,
    val moduleId: Long? = null,
    val tags: List<String> = emptyList(),
    val repeat: TaskRepeat? = null,
    val reminderMinutesBefore: Int? = null,
    val parentId: Long? = null,
    val location: String? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant = Instant.EPOCH,
    val sortOrder: Int = 0,
    val checklist: List<ChecklistItem> = emptyList(),
) {
    val isOpen: Boolean get() = completedAt == null
    val isDeadline: Boolean get() = kind == TaskKind.DEADLINE
    val isStep: Boolean get() = parentId != null
}

/** One entry of the Done log: [taskId] completed at [completedAt], for its occurrence due on [dueDate]. */
data class TaskCompletion(
    val id: Long = 0,
    val taskId: Long,
    val dueDate: LocalDate?,
    val completedAt: Instant,
)

/** Weekday sets as stored: Monday is bit 0, Sunday bit 6. */
object Weekdays {
    fun toBits(days: Set<DayOfWeek>): Int = days.fold(0) { bits, day -> bits or (1 shl (day.value - 1)) }

    fun fromBits(bits: Int): Set<DayOfWeek> = DayOfWeek.entries.filterTo(LinkedHashSet()) { bits and (1 shl (it.value - 1)) != 0 }
}
