package io.github.matteopi.budgeting.core.planner.day

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A stretch of time from [start] (included) to [end] (not included). Empty when [end] is not after [start]. */
data class Interval(val start: Instant, val end: Instant) {
    val isEmpty: Boolean get() = !end.isAfter(start)

    val minutes: Long get() = if (isEmpty) 0 else Duration.between(start, end).toMinutes()

    fun overlaps(other: Interval): Boolean = start.isBefore(other.end) && other.start.isBefore(end)

    operator fun contains(instant: Instant): Boolean = !instant.isBefore(start) && instant.isBefore(end)

    /** The part of this inside [other], or null when they don't overlap. */
    fun intersect(other: Interval): Interval? {
        val s = maxOf(start, other.start)
        val e = minOf(end, other.end)
        return if (e.isAfter(s)) Interval(s, e) else null
    }
}

/** Sets of intervals (docs/PLAN.md §13.4): merging, subtracting and totals. */
object Intervals {

    /** [intervals] sorted, with overlapping and touching ones joined. Empty ones are dropped. */
    fun merge(intervals: List<Interval>): List<Interval> {
        val sorted = intervals.filter { !it.isEmpty }.sortedBy { it.start }
        val result = ArrayList<Interval>(sorted.size)
        for (i in sorted) {
            val last = result.lastOrNull()
            if (last != null && !i.start.isAfter(last.end)) {
                if (i.end.isAfter(last.end)) result[result.size - 1] = Interval(last.start, i.end)
            } else {
                result += i
            }
        }
        return result
    }

    /** What is left of [base] once [cuts] are taken out, sorted. */
    fun subtract(base: List<Interval>, cuts: List<Interval>): List<Interval> {
        val holes = merge(cuts)
        val result = ArrayList<Interval>()
        for (b in merge(base)) {
            var from = b.start
            for (h in holes) {
                if (!h.end.isAfter(from)) continue
                if (!h.start.isBefore(b.end)) break
                if (h.start.isAfter(from)) result += Interval(from, h.start)
                if (h.end.isAfter(from)) from = h.end
            }
            if (b.end.isAfter(from)) result += Interval(from, b.end)
        }
        return result
    }

    fun subtract(base: Interval, cuts: List<Interval>): List<Interval> = subtract(listOf(base), cuts)

    /** The minutes [intervals] cover, overlaps counted once. */
    fun totalMinutes(intervals: List<Interval>): Long = merge(intervals).sumOf { it.minutes }

    /** The minutes of [a] that [b] covers. */
    fun overlapMinutes(a: Interval, b: List<Interval>): Long = totalMinutes(b.mapNotNull { it.intersect(a) })
}

/** The 15-minute grid drags snap to, on the local clock (docs/PLAN.md §13.2). */
object Snap {
    const val STEP_MINUTES = 15

    /**
     * [instant] moved to the nearest quarter hour of the local clock (a half-way time goes later). In the hour the
     * clocks go back, which happens twice, it stays in the same one.
     */
    fun snap(instant: Instant, zone: ZoneId): Instant {
        val zoned = instant.atZone(zone)
        val local = zoned.toLocalDateTime()
        val minuteOfDay = local.hour * 60 + local.minute + (if (local.second >= 30) 1 else 0)
        val snapped = ((minuteOfDay + STEP_MINUTES / 2) / STEP_MINUTES) * STEP_MINUTES
        val time: LocalDateTime = local.toLocalDate().atStartOfDay().plusMinutes(snapped.toLong())
        return ZonedDateTime.ofLocal(time, zone, zoned.offset).toInstant()
    }

    /** [time] on the local [date] of [reference], as an instant (a time in a spring-forward gap moves on by the gap). */
    fun at(reference: Instant, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(reference.atZone(zone).toLocalDate(), time, zone).toInstant()
}
