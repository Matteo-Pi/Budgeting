package io.github.matteopi.budgeting.core.planner.insights

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Whole days from [from] to [to], both included: a week, a month, or any stretch the insights look at. */
data class Period(val from: LocalDate, val to: LocalDate) {
    init {
        require(!to.isBefore(from)) { "A period ends on or after its start: $from to $to" }
    }

    val days: Int get() = (ChronoUnit.DAYS.between(from, to) + 1).toInt()

    val dates: List<LocalDate> get() = (0 until days).map { from.plusDays(it.toLong()) }

    /** The same number of days just before this one. */
    fun previous(): Period = Period(from.minusDays(days.toLong()), from.minusDays(1))

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)

    /** From the start of [from] to the end of [to] in [zone], as instants. */
    fun interval(zone: ZoneId): Interval = Interval(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())

    companion object {
        /** The week (Monday to Sunday) holding [date]. */
        fun week(date: LocalDate): Period = HabitProgress.weekStart(date).let { Period(it, it.plusDays(6)) }

        /** The calendar month holding [date]. */
        fun month(date: LocalDate): Period = Period(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()))
    }
}

/** A category's minutes in a period, and in the period of the same length before it. */
data class CategoryTime(val categoryId: Long, val minutes: Long, val previousMinutes: Long) {
    val change: Long get() = minutes - previousMinutes
}

/** One day's minutes per category id, within the day's record window. */
data class DayTime(val date: LocalDate, val minutes: Map<Long, Long>) {
    val total: Long get() = minutes.values.sum()
}

/** Where time went in [period]: each day, and each category against the period before (most time first). */
data class TimeSummary(val period: Period, val days: List<DayTime>, val categories: List<CategoryTime>) {
    val totalMinutes: Long get() = categories.sumOf { it.minutes }

    fun minutesOf(categoryId: Long): Long = categories.firstOrNull { it.categoryId == categoryId }?.minutes ?: 0
}

/**
 * Where time went (docs/PLAN.md §12.10, §13.4): minutes per category from the day record, each day clipped to its record
 * window, so a chunk across two days' windows counts on each for its own part and time outside every window (sleep, with
 * wake-to-sleep days) counts on none. Days are instants, so a clock-change day has its 23 or 25 hours.
 */
object TimeInsights {

    fun summary(records: List<RecordChunk>, period: Period, settings: DaySettings, zone: ZoneId): TimeSummary {
        val days = byDay(records, period, settings, zone)
        val before = byDay(records, period.previous(), settings, zone)
        val now = totals(days)
        val then = totals(before)
        val categories = (now.keys + then.keys)
            .map { CategoryTime(it, now[it] ?: 0, then[it] ?: 0) }
            .sortedWith(compareByDescending<CategoryTime> { it.minutes }.thenByDescending { it.previousMinutes }.thenBy { it.categoryId })
        return TimeSummary(period, days, categories)
    }

    /** Each day of [period] with its minutes per category. */
    fun byDay(records: List<RecordChunk>, period: Period, settings: DaySettings, zone: ZoneId): List<DayTime> = period.dates.map { date ->
        val window = DayWindows.recordWindow(date, settings, zone)
        val minutes = HashMap<Long, Long>()
        for (r in records) {
            val part = Interval(r.start, r.end).intersect(window) ?: continue
            if (part.minutes > 0) minutes[r.categoryId] = (minutes[r.categoryId] ?: 0) + part.minutes
        }
        DayTime(date, minutes)
    }

    private fun totals(days: List<DayTime>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        for (day in days) for ((id, m) in day.minutes) out[id] = (out[id] ?: 0) + m
        return out
    }
}
