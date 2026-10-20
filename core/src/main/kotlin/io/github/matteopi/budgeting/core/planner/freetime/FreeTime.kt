package io.github.matteopi.budgeting.core.planner.freetime

import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.Intervals
import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.timetable.TravelTimes
import io.github.matteopi.budgeting.core.planner.todo.TaskRules
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable

/** A fixed meal, in minutes after midnight, counted as busy (docs/PLAN.md §12.6). */
@Serializable
data class MealSlot(val startMinute: Int, val endMinute: Int)

/**
 * What free time leaves out (docs/PLAN.md §13.6): [meals], travel ([defaultBufferMinutes], and minutes from home by
 * place in [travelMinutes]) and gaps shorter than [minGapMinutes]. Its settings screen comes in stage P5.
 */
@Serializable
data class FreeSettings(
    val meals: List<MealSlot> = emptyList(),
    val defaultBufferMinutes: Int = 15,
    val travelMinutes: Map<String, Int> = emptyMap(),
    val minGapMinutes: Int = 30,
) {
    val travel: TravelTimes get() = TravelTimes(defaultBufferMinutes, travelMinutes)
}

/** Something that takes time: a class, a busy event or an exam, with where it is. */
data class BusyItem(val interval: Interval, val location: String = "")

/** A day's free stretches and their total. */
data class FreeDay(val free: List<Interval>) {
    val minutes: Long get() = free.sumOf { it.minutes }
}

/** Free time (docs/PLAN.md §13.4): the waking day minus what is busy, travel and meals, without the short gaps. */
object FreeTime {

    /** An exam without a length takes this long. */
    val DEFAULT_EXAM_LENGTH: Duration = Duration.ofHours(2)

    fun compute(waking: Interval, busy: List<BusyItem>, meals: List<Interval>, travel: TravelTimes, minGapMinutes: Int): FreeDay {
        val cuts = ArrayList<Interval>()
        var here: String? = null
        var last: BusyItem? = null
        for (item in busy.filter { !it.interval.isEmpty }.sortedBy { it.interval.start }) {
            cuts += item.interval
            if (item.location.isBlank()) continue
            // Getting there: from home for the first place of the day, else from the place before.
            val minutes = here?.let { travel.between(it, item.location) } ?: travel.fromHome(item.location)
            if (minutes > 0) cuts += Interval(item.interval.start.minus(Duration.ofMinutes(minutes.toLong())), item.interval.start)
            here = item.location
            if (last == null || !item.interval.end.isBefore(last.interval.end)) last = item
        }
        // And home again after the last place.
        last?.let { item ->
            val home = travel.fromHome(item.location)
            if (home > 0) cuts += Interval(item.interval.end, item.interval.end.plus(Duration.ofMinutes(home.toLong())))
        }
        cuts += meals
        return FreeDay(Intervals.subtract(waking, cuts).filter { it.minutes >= minGapMinutes })
    }

    /**
     * The busy items of a day: classes (not cancelled), busy timed events the app didn't write ([appEventIds]), and
     * timed exams for their length.
     */
    fun busyItems(
        occurrences: List<Occurrence>,
        instances: List<CalendarInstance>,
        tasks: List<Task>,
        zone: ZoneId,
        appEventIds: Set<Long> = emptySet(),
    ): List<BusyItem> =
        occurrences.filter { !it.cancelled }.map { BusyItem(Interval(it.start, it.end), it.location) } +
            instances.filter { !it.allDay && it.busy && it.eventId !in appEventIds }.map { BusyItem(Interval(it.start, it.end), it.location) } +
            tasks.filter { it.isOpen && TaskRules.isExam(it) && it.dueDate != null && it.dueTime != null }.map { exam ->
                val start = ZonedDateTime.of(exam.dueDate, exam.dueTime, zone).toInstant()
                val length = exam.lengthMinutes?.let { Duration.ofMinutes(it.toLong()) } ?: DEFAULT_EXAM_LENGTH
                BusyItem(Interval(start, start.plus(length)), exam.location ?: "")
            }

    /** The meal slots of [date] as instants. */
    fun meals(date: LocalDate, settings: FreeSettings, zone: ZoneId): List<Interval> = settings.meals.mapNotNull { slot ->
        if (slot.startMinute !in 0 until 24 * 60 || slot.endMinute !in 1..24 * 60 || slot.endMinute <= slot.startMinute) return@mapNotNull null
        val start = ZonedDateTime.of(date, LocalTime.of(slot.startMinute / 60, slot.startMinute % 60), zone).toInstant()
        val end = if (slot.endMinute == 24 * 60) {
            date.plusDays(1).atStartOfDay(zone).toInstant()
        } else {
            ZonedDateTime.of(date, LocalTime.of(slot.endMinute / 60, slot.endMinute % 60), zone).toInstant()
        }
        Interval(start, end)
    }

    /** [free] without the time [plans] already take: "3 h 20 m free (1 h planned)". */
    fun unplanned(free: List<Interval>, plans: List<PlanBlock>): List<Interval> =
        Intervals.subtract(free, plans.map { Interval(it.start, it.end) })

    /** The minutes of [free] (less [plans]) inside [range]. */
    fun unplannedMinutes(free: List<Interval>, plans: List<PlanBlock>, range: Interval): Long =
        Intervals.totalMinutes(unplanned(free, plans).mapNotNull { it.intersect(range) })
}
