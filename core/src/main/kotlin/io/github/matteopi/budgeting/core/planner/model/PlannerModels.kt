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

// ---- The timetable (docs/PLAN.md §12.4, stage P2) -------------------------------------------------

enum class EntryKind { LECTURE, SEMINAR, LAB, TUTORIAL, SPORT, SOCIETY, OTHER }

/**
 * Which weeks a timetable entry is on. The weekly patterns count teaching weeks from the start of each term (week 1
 * is the week the term starts in); [Once] is a single date.
 */
sealed interface WeekPattern {
    data object EveryWeek : WeekPattern
    data class TeachingWeeks(val weeks: Set<Int>) : WeekPattern
    data object OddWeeks : WeekPattern
    data object EvenWeeks : WeekPattern
    data class Once(val date: LocalDate) : WeekPattern
}

/**
 * A class or other regular commitment (docs/PLAN.md §12.4). [termId] limits it to one term (null: every term);
 * [inHolidays] keeps a weekly entry going outside terms too.
 */
data class TimetableEntry(
    val id: Long = 0,
    val title: String,
    val kind: EntryKind = EntryKind.LECTURE,
    val moduleId: Long? = null,
    val location: String = "",
    val room: String = "",
    val contact: String = "",
    val colorArgb: Long? = null,
    val note: String = "",
    val weekday: DayOfWeek = DayOfWeek.MONDAY,
    val start: LocalTime = LocalTime.of(9, 0),
    val end: LocalTime = LocalTime.of(10, 0),
    val pattern: WeekPattern = WeekPattern.EveryWeek,
    val termId: Long? = null,
    val inHolidays: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Instant = Instant.EPOCH,
)

/** One date of an entry changed: cancelled, or moved to other times or another room. */
data class EntryException(
    val id: Long = 0,
    val entryId: Long,
    val date: LocalDate,
    val cancelled: Boolean = false,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val room: String? = null,
)

/** One dated class, expanded from an entry (with its exception applied). */
data class Occurrence(
    val entryId: Long,
    val date: LocalDate,
    val start: Instant,
    val end: Instant,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val title: String,
    val kind: EntryKind,
    val moduleId: Long?,
    val location: String,
    val room: String,
    val colorArgb: Long?,
    /** True when an exception moved it or changed its room for this date. */
    val changed: Boolean = false,
    /** True when it is cancelled for this date (only listed when asked for, so it can be restored). */
    val cancelled: Boolean = false,
) {
    /** The stable key of this occurrence, for calendar sync and the day record: "entry:12:2026-10-20". */
    val key: String get() = "entry:$entryId:$date"
}

/** An event read from a calendar on the phone (docs/PLAN.md §12.5). */
data class CalendarInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    /** False for events marked "free", which don't take free time. */
    val busy: Boolean,
    val colorArgb: Long?,
)

/** One event the app wrote to a calendar: [itemKey] is what it shows ("task:4", "entry:12:2026-10-20"). */
data class CalendarLink(
    val id: Long = 0,
    val itemKey: String,
    val calendarId: Long,
    val eventId: Long,
    val uid: String,
    val contentHash: String,
)

// ---- The day record (docs/PLAN.md §12.8, stage P3) ----

/** The categories the app knows by meaning, whatever the user renames them to. */
enum class CategoryKey { STUDY, CLASS, EXERCISE, SOCIAL, COOKING, TRAVEL, CHORES, REST, PHONE, SLEEP, OTHER }

/** What a chunk of the day was spent on: "Study", "Lecture or class", or one of the user's own. */
data class TimeCategory(
    val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val systemKey: CategoryKey? = null,
    /** Chunks of this category can carry a focus rating (study and classes). */
    val ratesFocus: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

/** A stretch of time with an id, so the day's edits can treat records and plans alike. */
interface TimeChunk<T : TimeChunk<T>> {
    val id: Long
    val start: Instant
    val end: Instant

    fun withTimes(start: Instant, end: Instant): T

    fun withId(id: Long): T
}

/** Where a plan block came from. */
enum class PlanOrigin { USER, SUGGESTION, HABIT }

/** How a plan block went. */
enum class Outcome { DONE, PARTLY, SKIPPED }

/** Time planned ahead: "Study EC101, 14:00–15:30". Its [outcome] is worked out from the record unless overridden. */
data class PlanBlock(
    override val id: Long = 0,
    override val start: Instant,
    override val end: Instant,
    val categoryId: Long,
    val moduleId: Long? = null,
    val taskId: Long? = null,
    val habitId: Long? = null,
    val topicId: Long? = null,
    val origin: PlanOrigin = PlanOrigin.USER,
    val outcomeOverride: Outcome? = null,
    val note: String = "",
    val createdAt: Instant = Instant.EPOCH,
) : TimeChunk<PlanBlock> {
    override fun withTimes(start: Instant, end: Instant) = copy(start = start, end = end)

    override fun withId(id: Long) = copy(id = id)
}

/** How a record chunk was made. */
enum class RecordOrigin { MANUAL, TIMER, CONFIRMED }

/**
 * Time recorded: what really happened. [planBlockId] or [sourceKey] (a class "entry:12:2026-10-20", an event
 * "event:345:1729425600000") say what it confirmed.
 */
data class RecordChunk(
    override val id: Long = 0,
    override val start: Instant,
    override val end: Instant,
    val categoryId: Long,
    val moduleId: Long? = null,
    val taskId: Long? = null,
    val planBlockId: Long? = null,
    val sourceKey: String? = null,
    /** 1 to 5, for categories that rate focus. */
    val focus: Int? = null,
    val note: String = "",
    val origin: RecordOrigin = RecordOrigin.MANUAL,
    val createdAt: Instant = Instant.EPOCH,
) : TimeChunk<RecordChunk> {
    override fun withTimes(start: Instant, end: Instant) = copy(start = start, end = end)

    override fun withId(id: Long) = copy(id = id)
}

/** The end-of-day review of [date]: finished or not, with an optional rating (1 to 5) and note. */
data class DayReview(
    val date: LocalDate,
    val finishedAt: Instant? = null,
    val rating: Int? = null,
    val note: String = "",
)

// ---- Habits (docs/PLAN.md §12.9, stage P4) ----

/**
 * What a habit aims for: [ON_DAYS] each chosen day; [TIMES_PER_WEEK] a number of times a week; [AT_LEAST] an amount
 * per day or week; [AT_MOST] a limit per day or week.
 */
enum class GoalKind { ON_DAYS, TIMES_PER_WEEK, AT_LEAST, AT_MOST }

/** What a habit's amounts count: times (ticks or sessions), minutes, or a number of something ("pages"). */
enum class GoalUnit { TIMES, MINUTES, COUNT }

enum class GoalPeriod { DAY, WEEK }

/** How a habit is filled in: ticked by hand, counted from the day record by a category, or both (the larger counts). */
enum class HabitCounting { MANUAL, RECORD, BOTH }

/**
 * A habit's goal. [days] are the chosen days of [GoalKind.ON_DAYS], and of a daily [GoalKind.AT_LEAST] or
 * [GoalKind.AT_MOST] (empty: every day). [GoalKind.ON_DAYS] always means once on each chosen day, and
 * [GoalKind.TIMES_PER_WEEK] [target] times a week.
 */
data class HabitGoal(
    val kind: GoalKind,
    val target: Int = 1,
    val unit: GoalUnit = GoalUnit.TIMES,
    val period: GoalPeriod = GoalPeriod.DAY,
    val days: Set<DayOfWeek> = emptySet(),
) {
    /** True when the goal is judged per week (its streak counts weeks). */
    val weekly: Boolean
        get() = kind == GoalKind.TIMES_PER_WEEK || (kind != GoalKind.ON_DAYS && period == GoalPeriod.WEEK)

    /** The amount a day or week needs: 1 for [GoalKind.ON_DAYS], else [target]. */
    val effectiveTarget: Int get() = if (kind == GoalKind.ON_DAYS) 1 else target
}

/**
 * A habit (docs/PLAN.md §12.9). [minMinutes] is the shortest record chunk of [categoryId] that counts as once.
 * [pausedFrom] to [pausedUntil] (both included; no end: paused until resumed) and, for [termOnly], days outside the
 * terms don't count against it. Days before [startDate] don't count at all. The slot fields are for *Suggest a
 * time* (stage P5).
 */
data class Habit(
    val id: Long = 0,
    val name: String,
    val colorArgb: Long = 0xFF2E8B57,
    val goal: HabitGoal,
    val counting: HabitCounting = HabitCounting.MANUAL,
    val categoryId: Long? = null,
    val minMinutes: Int? = null,
    val streaks: Boolean = true,
    val reminderTime: LocalTime? = null,
    val termOnly: Boolean = false,
    val pausedFrom: LocalDate? = null,
    val pausedUntil: LocalDate? = null,
    val suggestSlot: Boolean = false,
    val slotMinutes: Int? = null,
    val slotStart: LocalTime? = null,
    val slotEnd: LocalTime? = null,
    val startDate: LocalDate,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
) {
    val countsRecord: Boolean get() = counting != HabitCounting.MANUAL
    val countsByHand: Boolean get() = counting != HabitCounting.RECORD
}

/** A habit's day filled in by hand: [amount] (1 for a tick), or [skipped] ("Skip today": ill, away). */
data class HabitEntry(
    val id: Long = 0,
    val habitId: Long,
    val date: LocalDate,
    val amount: Int = 0,
    val skipped: Boolean = false,
)

// ---- Study suggestions (docs/PLAN.md §12.7, stage P5) ----

/** A topic to revise for an exam (a deadline of type exam): [difficulty] 1 (easy) to 3 (hard). */
data class RevisionTopic(
    val id: Long = 0,
    val examTaskId: Long,
    val name: String,
    val difficulty: Int = 2,
    val sortOrder: Int = 0,
)

/** A suggestion the user dismissed ([key] is the demand's key and the start), kept 30 days so it isn't offered again. */
data class DismissedSuggestion(val key: String, val dismissedAt: Instant)
