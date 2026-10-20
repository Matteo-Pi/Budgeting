package io.github.matteopi.budgeting.ui.planner.timetable

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.timetable.ClashKind
import io.github.matteopi.budgeting.core.planner.timetable.Clashes
import io.github.matteopi.budgeting.core.planner.timetable.Lanes
import io.github.matteopi.budgeting.core.planner.timetable.TeachingWeeks
import io.github.matteopi.budgeting.core.planner.todo.TaskRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** What a timetable item is. */
enum class ItemKind { CLASS, CANCELLED, EVENT, EXAM, TASK }

/**
 * One block on the timetable (docs/PLAN.md §13.9): a class, a cancelled class, a calendar event, an exam or a timed
 * deadline. [startMinute] and [endMinute] are minutes after the day's midnight (an end past midnight is capped at
 * 24:00); [lane] of [lanes] side by side.
 */
@Immutable
data class TimetableItemUi(
    val key: String,
    val kind: ItemKind,
    val title: String,
    val detail: String,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val startMinute: Int,
    val endMinute: Int,
    val colorArgb: Long?,
    val lane: Int = 0,
    val lanes: Int = 1,
    val entryId: Long? = null,
    val eventId: Long? = null,
    val taskId: Long? = null,
    val changed: Boolean = false,
    /** A class's room this week (for *Change this week*). */
    val room: String = "",
) {
    val timeText: String get() = PlannerText.range(start, end)
}

@Immutable
data class DayColumnUi(
    val date: LocalDate,
    /** "Mon 19". */
    val label: String,
    val isToday: Boolean,
    val items: List<TimetableItemUi>,
    val allDay: List<String>,
)

@Immutable
data class WeekUi(
    val weekStart: LocalDate,
    /** "19–25 Oct". */
    val title: String,
    /** "Week 5 · Autumn", "Holidays", or "" without term dates. */
    val termLabel: String,
    val firstHour: Int,
    val lastHour: Int,
    val days: List<DayColumnUi>,
    val clashes: List<String>,
)

@Immutable
data class MonthDayUi(val date: LocalDate, val inMonth: Boolean, val isToday: Boolean, val count: Int, val colours: List<Long>)

/** The timetable's pure mapping (docs/PLAN.md §13.9), tested on the JVM. */
object TimetableMapping {

    const val DEFAULT_FIRST_HOUR = 8
    const val DEFAULT_LAST_HOUR = 18

    private const val EXAM_COLOUR = 0xFFD1495B
    private const val EVENT_COLOUR = 0xFF8D6E63

    fun weekStart(date: LocalDate): LocalDate = TeachingWeeks.weekStart(date)

    /** "Week 5 · Autumn", "Holidays", or "" when there are no term dates. */
    fun termLabel(date: LocalDate, terms: List<TermPeriod>): String {
        if (terms.isEmpty()) return ""
        val term = Regimes.termContaining(date, terms) ?: Regimes.termContaining(date.plusDays(6), terms)
            ?: return "Holidays"
        val inside = if (date.isBefore(term.startDate)) term.startDate else date
        return "Week ${TeachingWeeks.number(inside, term)} · ${term.name}"
    }

    /** All the items of [date], each placed in its lane. */
    fun dayItems(
        date: LocalDate,
        occurrences: List<Occurrence>,
        events: List<CalendarInstance>,
        tasks: List<Task>,
        modules: List<Module>,
        zone: ZoneId,
    ): List<TimetableItemUi> {
        val modulesById = modules.associateBy { it.id }
        val items = mutableListOf<TimetableItemUi>()
        for (o in occurrences.filter { it.date == date }) {
            val colour = o.colorArgb ?: o.moduleId?.let { modulesById[it]?.colorArgb }
            items += TimetableItemUi(
                key = o.key,
                kind = if (o.cancelled) ItemKind.CANCELLED else ItemKind.CLASS,
                title = o.title,
                detail = listOf(kindLabel(o.kind), o.room, o.location).filter { it.isNotBlank() }.joinToString(" · ") +
                    if (o.cancelled) " · Cancelled this week" else if (o.changed) " · Changed this week" else "",
                date = date,
                start = o.startTime,
                end = o.endTime,
                startMinute = minuteOf(o.startTime),
                endMinute = endMinuteOf(o.startTime, o.endTime),
                colorArgb = colour,
                entryId = o.entryId,
                changed = o.changed,
                room = o.room,
            )
        }
        for (e in events.filter { !it.allDay }) {
            val start = e.start.atZone(zone)
            val end = e.end.atZone(zone)
            if (start.toLocalDate() != date) continue
            val endMinute = if (end.toLocalDate() == date) minuteOf(end.toLocalTime()) else 24 * 60
            items += TimetableItemUi(
                key = "event:${e.eventId}:${e.start.toEpochMilli()}",
                kind = ItemKind.EVENT,
                title = e.title.ifBlank { "(No title)" },
                detail = e.location,
                date = date,
                start = start.toLocalTime(),
                end = end.toLocalTime(),
                startMinute = minuteOf(start.toLocalTime()),
                endMinute = maxOf(endMinute, minuteOf(start.toLocalTime()) + 15),
                colorArgb = e.colorArgb ?: EVENT_COLOUR,
                eventId = e.eventId,
            )
        }
        for (t in tasks.filter { it.isOpen && it.isDeadline && it.dueDate == date && it.dueTime != null }) {
            val start = t.dueTime!!
            val length = t.lengthMinutes ?: 30
            val end = start.plusMinutes(length.toLong())
            val exam = TaskRules.isExam(t)
            items += TimetableItemUi(
                key = "task:${t.id}",
                kind = if (exam) ItemKind.EXAM else ItemKind.TASK,
                title = t.title,
                detail = listOfNotNull(if (exam) "Exam" else "Due", t.location).joinToString(" · "),
                date = date,
                start = start,
                end = end,
                startMinute = minuteOf(start),
                endMinute = endMinuteOf(start, end),
                colorArgb = if (exam) EXAM_COLOUR else t.moduleId?.let { modulesById[it]?.colorArgb } ?: EXAM_COLOUR,
                taskId = t.id,
            )
        }
        return Lanes.assign(items, { it.startMinute.toLong() }, { it.endMinute.toLong() })
            .map { it.item.copy(lane = it.lane, lanes = it.lanes) }
            .sortedWith(compareBy<TimetableItemUi> { it.startMinute }.thenBy { it.endMinute }.thenBy { it.key })
    }

    fun week(
        weekStart: LocalDate,
        today: LocalDate,
        occurrences: List<Occurrence>,
        events: List<CalendarInstance>,
        tasks: List<Task>,
        modules: List<Module>,
        terms: List<TermPeriod>,
        zone: ZoneId,
    ): WeekUi {
        val days = (0L..6L).map { weekStart.plusDays(it) }.map { date ->
            DayColumnUi(
                date = date,
                label = "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${date.dayOfMonth}",
                isToday = date == today,
                items = dayItems(date, occurrences, events, tasks, modules, zone),
                allDay = allDay(date, events, zone),
            )
        }
        val all = days.flatMap { it.items }
        val first = minOf(DEFAULT_FIRST_HOUR, all.minOfOrNull { it.startMinute / 60 } ?: DEFAULT_FIRST_HOUR)
        val last = maxOf(DEFAULT_LAST_HOUR, all.maxOfOrNull { (it.endMinute + 59) / 60 } ?: DEFAULT_LAST_HOUR).coerceAtMost(24)
        val clashes = Clashes.find(occurrences.filter { !it.cancelled && it.date >= weekStart && it.date <= weekStart.plusDays(6) })
            .map { c ->
                val day = c.first.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                when (c.kind) {
                    ClashKind.OVERLAP -> "$day: ${c.first.title} and ${c.second.title} overlap"
                    ClashKind.TIGHT -> "$day: only ${c.gapMinutes} min from ${c.first.title} to ${c.second.title} at ${c.second.location}"
                }
            }
        return WeekUi(weekStart, weekTitle(weekStart), termLabel(weekStart, terms), first, last, days, clashes)
    }

    /** "19–25 Oct", "28 Sep – 4 Oct", "28 Dec 2026 – 3 Jan 2027". */
    fun weekTitle(weekStart: LocalDate): String {
        val end = weekStart.plusDays(6)
        fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return when {
            weekStart.year != end.year -> "${weekStart.dayOfMonth} ${month(weekStart)} ${weekStart.year} – ${end.dayOfMonth} ${month(end)} ${end.year}"
            weekStart.month != end.month -> "${weekStart.dayOfMonth} ${month(weekStart)} – ${end.dayOfMonth} ${month(end)}"
            else -> "${weekStart.dayOfMonth}–${end.dayOfMonth} ${month(end)}"
        }
    }

    /** The all-day events of [date] by title. */
    fun allDay(date: LocalDate, events: List<CalendarInstance>, zone: ZoneId): List<String> = events.filter { e ->
        e.allDay && !date.isBefore(e.start.atZone(zone).toLocalDate()) && date.isBefore(e.end.atZone(zone).toLocalDate())
    }.map { it.title.ifBlank { "(No title)" } }

    /** Six weeks from the Monday on or before the 1st of [month]'s month, with a count and up to 3 colours per day. */
    fun month(
        month: LocalDate,
        today: LocalDate,
        occurrences: List<Occurrence>,
        events: List<CalendarInstance>,
        tasks: List<Task>,
        modules: List<Module>,
        zone: ZoneId,
    ): List<MonthDayUi> {
        val first = month.withDayOfMonth(1)
        val start = weekStart(first)
        return (0L until 42L).map { start.plusDays(it) }.map { date ->
            val items = dayItems(date, occurrences, events, tasks, modules, zone).filter { it.kind != ItemKind.CANCELLED }
            MonthDayUi(date, date.month == first.month, date == today, items.size, items.mapNotNull { it.colorArgb }.distinct().take(3))
        }
    }

    fun kindLabel(kind: EntryKind): String = when (kind) {
        EntryKind.LECTURE -> "Lecture"
        EntryKind.SEMINAR -> "Seminar"
        EntryKind.LAB -> "Lab"
        EntryKind.TUTORIAL -> "Tutorial"
        EntryKind.SPORT -> "Sport"
        EntryKind.SOCIETY -> "Society"
        EntryKind.OTHER -> "Other"
    }

    /** Monday-first weekday names, for the editor. */
    val WEEKDAYS: List<DayOfWeek> = DayOfWeek.entries

    private fun minuteOf(time: LocalTime) = time.hour * 60 + time.minute

    /** An end at or before the start runs past midnight: it is drawn to the end of the day. */
    private fun endMinuteOf(start: LocalTime, end: LocalTime): Int =
        if (end.isAfter(start)) minuteOf(end) else 24 * 60
}
