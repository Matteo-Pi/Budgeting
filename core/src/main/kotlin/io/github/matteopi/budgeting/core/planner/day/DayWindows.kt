package io.github.matteopi.budgeting.core.planner.day

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable

/** Where a day of the record starts and ends (docs/PLAN.md §12.8). */
enum class DayMode {
    /** From that weekday's wake time to its sleep time; sleep isn't recorded. */
    WAKE_SLEEP,

    /** From a fixed cut-off to the same cut-off the next day, so a late night counts as the day before. */
    FIXED,
}

/**
 * The user's day (docs/PLAN.md §13.6): the record's [mode], the [cutoffMinute] for [DayMode.FIXED], and the wake and
 * sleep times of each weekday (Monday first), all in minutes after midnight. A sleep time at or before the wake time
 * is on the next date. Stored as JSON; a missing field takes its default.
 */
@Serializable
data class DaySettings(
    val mode: DayMode = DayMode.WAKE_SLEEP,
    val cutoffMinute: Int = 4 * 60,
    val wakeMinutes: List<Int> = List(7) { 8 * 60 },
    val sleepMinutes: List<Int> = List(7) { 0 },
) {
    fun wake(day: DayOfWeek): LocalTime = timeOf(wakeMinutes.getOrNull(day.value - 1) ?: 8 * 60)

    fun sleep(day: DayOfWeek): LocalTime = timeOf(sleepMinutes.getOrNull(day.value - 1) ?: 0)

    val cutoff: LocalTime get() = timeOf(cutoffMinute)

    /** The same settings with wrong values (out of range, a list of the wrong length) set back to the defaults. */
    fun sanitized(): DaySettings {
        val defaults = DaySettings()
        fun fix(list: List<Int>, fallback: List<Int>) =
            if (list.size == 7 && list.all { it in 0 until MINUTES_PER_DAY }) list else fallback
        return copy(
            cutoffMinute = cutoffMinute.takeIf { it in 0 until MINUTES_PER_DAY } ?: defaults.cutoffMinute,
            wakeMinutes = fix(wakeMinutes, defaults.wakeMinutes),
            sleepMinutes = fix(sleepMinutes, defaults.sleepMinutes),
        )
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        private fun timeOf(minute: Int): LocalTime {
            val m = minute.coerceIn(0, MINUTES_PER_DAY - 1)
            return LocalTime.of(m / 60, m % 60)
        }
    }
}

/**
 * The windows of a day (docs/PLAN.md §13.2). Chunks belong to no day: a day shows whatever overlaps its window, so
 * changing these settings never loses or moves anything.
 */
object DayWindows {

    /** The day record's window of [date]: wake to sleep, or cut-off to cut-off. */
    fun recordWindow(date: LocalDate, settings: DaySettings, zone: ZoneId): Interval = when (settings.mode) {
        DayMode.WAKE_SLEEP -> wakingWindow(date, settings, zone)
        DayMode.FIXED -> Interval(
            ZonedDateTime.of(date, settings.cutoff, zone).toInstant(),
            ZonedDateTime.of(date.plusDays(1), settings.cutoff, zone).toInstant(),
        )
    }

    /** Wake to sleep on [date], whatever the record's mode: free time uses it. */
    fun wakingWindow(date: LocalDate, settings: DaySettings, zone: ZoneId): Interval {
        val wake = settings.wake(date.dayOfWeek)
        val sleep = settings.sleep(date.dayOfWeek)
        val sleepDate = if (sleep.isAfter(wake)) date else date.plusDays(1)
        return Interval(ZonedDateTime.of(date, wake, zone).toInstant(), ZonedDateTime.of(sleepDate, sleep, zone).toInstant())
    }

    /**
     * The day [now] belongs to: the date whose record window holds it. Between one day's end and the next one's
     * start (asleep, with [DayMode.WAKE_SLEEP]) it is the coming day, before its start.
     */
    fun currentDay(now: Instant, settings: DaySettings, zone: ZoneId): LocalDate {
        val date = now.atZone(zone).toLocalDate()
        val window = recordWindow(date, settings, zone)
        if (!now.isBefore(window.start)) {
            // Past today's end (a window ending before midnight) it is still today until tomorrow's starts.
            return date
        }
        val yesterday = date.minusDays(1)
        return if (now.isBefore(recordWindow(yesterday, settings, zone).end)) yesterday else date
    }
}
