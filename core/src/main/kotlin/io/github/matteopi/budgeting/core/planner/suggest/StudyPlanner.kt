package io.github.matteopi.budgeting.core.planner.suggest

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.Intervals
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What a demand is for, in the order they are placed (docs/PLAN.md §13.4). */
enum class DemandKind { DEADLINE, REVISION, HABIT, MODULE }

/**
 * Time something needs (docs/PLAN.md §13.4): [minutes] between [earliest] (null: now) and the end of [latest], in
 * blocks of at most [blockMinutes] (null: the longest block), inside [window] each day if set. [study] demands count
 * against the study caps and days off. [reason] explains it, and [deadlineLeft] adds the free time before then.
 */
data class Demand(
    val key: String,
    val kind: DemandKind,
    val title: String,
    val minutes: Int,
    val latest: LocalDate,
    val earliest: LocalDate? = null,
    val blockMinutes: Int? = null,
    val window: TimeWindow? = null,
    val study: Boolean = true,
    val moduleId: Long? = null,
    val taskId: Long? = null,
    val habitId: Long? = null,
    val topicId: Long? = null,
    /** The category of a habit's blocks; study demands use the study category. */
    val categoryId: Long? = null,
    val reason: String,
    /** A deadline's work left: its reason then says how much free time there is before [latest]. */
    val deadlineLeft: Int? = null,
)

/** A proposed block: [key] is the demand's key and the start, so a dismissed one stays dismissed. */
data class Suggestion(val key: String, val interval: Interval, val demand: Demand, val reason: String)

/**
 * The planner's input (docs/PLAN.md §13.4): [free] is the unplanned free time of the coming days (busy and planned time
 * already taken out); [usedByDay] the study already planned or recorded per date, which counts against the caps;
 * [daysOff] dates switched off for study; [dismissed] suggestion keys.
 */
data class StudyInput(
    val now: Instant,
    val zone: ZoneId,
    val free: List<Interval>,
    val demands: List<Demand>,
    val prefs: StudyPrefs,
    val dismissed: Set<String> = emptySet(),
    val daysOff: Set<LocalDate> = emptySet(),
    val usedByDay: Map<LocalDate, Int> = emptyMap(),
    val focus: FocusProfile? = null,
)

/**
 * Suggested study time (docs/PLAN.md §12.7, §13.4): each demand in turn gets blocks carved from the free time, best
 * slot first. Deterministic, never over busy or planned time, and within every cap.
 */
object StudyPlanner {

    const val GRID_MINUTES = 15L

    /** How far ahead suggestions go. */
    const val HORIZON_DAYS = 14L

    fun suggest(input: StudyInput): List<Suggestion> {
        val prefs = input.prefs.sanitized()
        if (!prefs.on) return emptyList()
        val zone = input.zone
        var free = Intervals.merge(input.free).mapNotNull { it.intersect(Interval(input.now, maxOf(it.end, input.now))) }
        val used = input.usedByDay.toMutableMap()
        val out = ArrayList<Suggestion>()

        for (demand in input.demands) {
            var remaining = demand.minutes
            val skipped = ArrayList<Interval>()
            val longest = demand.blockMinutes ?: prefs.longestMinutes
            while (remaining > 0) {
                val slot = candidates(demand, free, skipped, input, prefs).firstOrNull() ?: break
                val date = slot.piece.start.atZone(zone).toLocalDate()
                val slotLeft = slot.piece.minutes.toInt()
                val dayLeft = if (demand.study) prefs.dailyCapMinutes - (used[date] ?: 0) else Int.MAX_VALUE
                val weekLeft = if (demand.study && prefs.weeklyCapMinutes != null) prefs.weeklyCapMinutes - weekUsed(used, date) else Int.MAX_VALUE
                val wanted = roundUp(remaining)
                var length = minOf(longest, slotLeft, dayLeft, weekLeft, wanted)
                if (length < prefs.shortestMinutes) {
                    // Only a demand's last piece may be short, and then it is made up to the shortest block if that fits.
                    val last = wanted < prefs.shortestMinutes
                    length = if (last && minOf(slotLeft, dayLeft, weekLeft) >= prefs.shortestMinutes) prefs.shortestMinutes else 0
                }
                if (length <= 0) {
                    skipped += slot.piece
                    continue
                }
                val block = Interval(slot.piece.start, slot.piece.start.plus(Duration.ofMinutes(length.toLong())))
                val key = "${demand.key}@${block.start.toEpochMilli()}"
                if (key in input.dismissed) {
                    skipped += block
                    continue
                }
                out += Suggestion(key, block, demand, reasonOf(demand, input.free, input.now, zone))
                // The block and the break after it (within the same free stretch) leave the free time.
                val breakEnd = minOf(block.end.plus(Duration.ofMinutes(prefs.breakMinutes.toLong())), slot.stretchEnd)
                free = Intervals.subtract(free, listOf(Interval(block.start, breakEnd)))
                if (demand.study) used[date] = (used[date] ?: 0) + length
                remaining -= length
            }
        }
        return out.sortedBy { it.interval.start }
    }

    /**
     * Where [suggestion] could move instead (docs/PLAN.md §13.9, the weekly review's *Move*): the free pieces the
     * planner would use for its demand, best first, with the time of [others] (the other suggestions) taken out. Each
     * is as long as the suggestion and starts its piece; its own time is not offered.
     */
    fun alternatives(input: StudyInput, suggestion: Suggestion, others: List<Suggestion>, limit: Int = 5): List<Interval> {
        val prefs = input.prefs.sanitized()
        val length = Duration.between(suggestion.interval.start, suggestion.interval.end)
        val ahead = Intervals.merge(input.free).mapNotNull { it.intersect(Interval(input.now, maxOf(it.end, input.now))) }
        val free = Intervals.subtract(ahead, others.filter { it.key != suggestion.key }.map { it.interval })
        return candidates(suggestion.demand, free, listOf(suggestion.interval), input, prefs)
            .filter { it.piece.minutes >= length.toMinutes() }
            .map { Interval(it.piece.start, it.piece.start.plus(length)) }
            .distinct()
            .take(limit)
    }

    private class Candidate(val piece: Interval, val stretchEnd: Instant, val score: Double)

    /**
     * The pieces of free time [demand] can use, best first: inside its dates (and window), outside avoided times and,
     * for study, days off; started on the 15-minute grid; split at preferred times, which score +2, plus the focus
     * bonus when learning is on. Ties go to the earliest.
     */
    private fun candidates(demand: Demand, free: List<Interval>, skipped: List<Interval>, input: StudyInput, prefs: StudyPrefs): List<Candidate> {
        val zone = input.zone
        val from = maxOf(input.now, demand.earliest?.atStartOfDay(zone)?.toInstant() ?: input.now)
        val until = demand.latest.plusDays(1).atStartOfDay(zone).toInstant()
        if (!until.isAfter(from)) return emptyList()
        val result = ArrayList<Candidate>()
        for (stretch in free) {
            val inRange = stretch.intersect(Interval(from, until)) ?: continue
            for (day in daysOf(inRange, zone)) {
                if (demand.study && (day.date in input.daysOff || day.date.dayOfWeek in prefs.daysOff)) continue
                var pieces = listOf(day.interval)
                if (demand.window != null) pieces = pieces.mapNotNull { it.intersect(window(day.date, demand.window, zone)) }
                if (demand.study) pieces = Intervals.subtract(pieces, prefs.avoided.map { window(day.date, it, zone) })
                pieces = Intervals.subtract(pieces, skipped)
                val preferred = prefs.preferred.map { window(day.date, it, zone) }
                for (piece in pieces) {
                    for (part in splitAt(piece, preferred)) {
                        val start = roundUpToGrid(part.start, zone)
                        if (!part.end.isAfter(start)) continue
                        val usable = Interval(start, part.end)
                        if (usable.minutes < GRID_MINUTES) continue
                        var score = if (preferred.any { it.intersect(usable) == usable }) 2.0 else 0.0
                        if (prefs.learnFromRecord && demand.study) score += input.focus?.bonus(usable.start, zone) ?: 0.0
                        result += Candidate(usable, stretch.end, score)
                    }
                }
            }
        }
        return result.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.piece.start })
    }

    private data class DayPart(val date: LocalDate, val interval: Interval)

    /** [interval] cut at local midnights. */
    private fun daysOf(interval: Interval, zone: ZoneId): List<DayPart> {
        val parts = ArrayList<DayPart>()
        var date = interval.start.atZone(zone).toLocalDate()
        while (true) {
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            Interval(dayStart, dayEnd).intersect(interval)?.let { parts += DayPart(date, it) }
            if (!dayEnd.isBefore(interval.end)) break
            date = date.plusDays(1)
        }
        return parts
    }

    private fun window(date: LocalDate, window: TimeWindow, zone: ZoneId): Interval {
        fun at(minute: Int): Instant =
            if (minute >= 24 * 60) date.plusDays(1).atStartOfDay(zone).toInstant()
            else ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), zone).toInstant()
        return Interval(at(window.startMinute), at(window.endMinute))
    }

    /** [piece] cut where [windows] start and end, so each part is wholly inside or outside them. */
    private fun splitAt(piece: Interval, windows: List<Interval>): List<Interval> {
        val cuts = windows.flatMap { listOf(it.start, it.end) }.filter { it.isAfter(piece.start) && it.isBefore(piece.end) }.distinct().sorted()
        val points = listOf(piece.start) + cuts + piece.end
        return points.zipWithNext { a, b -> Interval(a, b) }.filter { !it.isEmpty }
    }

    private fun roundUpToGrid(at: Instant, zone: ZoneId): Instant {
        val local: LocalDateTime = at.atZone(zone).toLocalDateTime()
        val minute = local.hour * 60 + local.minute
        val extra = (if (local.second > 0 || local.nano > 0 || minute % GRID_MINUTES != 0L) 1 else 0)
        if (extra == 0) return at
        val rounded = (minute / GRID_MINUTES + 1) * GRID_MINUTES
        val time = local.toLocalDate().atStartOfDay().plusMinutes(rounded)
        return ZonedDateTime.ofLocal(time, zone, at.atZone(zone).offset).toInstant()
    }

    private fun roundUp(minutes: Int): Int = ((minutes + GRID_MINUTES - 1) / GRID_MINUTES * GRID_MINUTES).toInt()

    private fun weekUsed(used: Map<LocalDate, Int>, date: LocalDate): Int {
        val monday = HabitProgress.weekStart(date)
        return (0L..6L).sumOf { used[monday.plusDays(it)] ?: 0 }
    }

    /** The reason, with a deadline's free time before its latest date: "EC101 essay due Fri: 6 h left, 9 h free before then". */
    private fun reasonOf(demand: Demand, free: List<Interval>, now: Instant, zone: ZoneId): String {
        val left = demand.deadlineLeft ?: return demand.reason
        val until = demand.latest.plusDays(1).atStartOfDay(zone).toInstant()
        val before = if (until.isAfter(now)) Intervals.totalMinutes(free.mapNotNull { it.intersect(Interval(now, until)) }) else 0
        return "${demand.reason}: ${PlannerText.duration(left)} left, ${PlannerText.duration(before.toInt())} free before then"
    }

    /** The Sunday of [date]'s week: the latest date of a weekly demand. */
    fun weekEnd(date: LocalDate): LocalDate = HabitProgress.weekStart(date).plusDays(6)
}
