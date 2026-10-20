package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How the Planner writes durations, times and dates (docs/PLAN.md §13.9), in one place so they are tested once. */
object PlannerText {

    // Locale.ENGLISH, not Locale.UK: current CLDR data abbreviates September as "Sept" for en-GB.
    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** "45 m", "2 h", "3 h 20 m"; never negative. */
    fun duration(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        val hours = m / 60
        val rest = m % 60
        return when {
            hours == 0 -> "$rest m"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest m"
        }
    }

    /** "14:05". */
    fun time(time: LocalTime): String = time.format(CLOCK)

    /** "13:00–14:30". */
    fun range(start: LocalTime, end: LocalTime): String = "${time(start)}–${time(end)}"

    /** "Fri 23 Oct", with the year when it isn't [today]'s. */
    fun date(date: LocalDate, today: LocalDate): String =
        date.format(if (date.year == today.year) DAY_MONTH else DAY_MONTH_YEAR)

    /** "Today", "Tomorrow", "Yesterday", the weekday within the coming week ("Friday"), else [date]. */
    fun day(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days == -1L -> "Yesterday"
            days in 2..6 -> date.format(WEEKDAY)
            else -> date(date, today)
        }
    }

    /** "today", "tomorrow", "in 3 days", "yesterday", "3 days ago". */
    fun countdown(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days == -1L -> "yesterday"
            days > 1 -> "in $days days"
            else -> "${-days} days ago"
        }
    }

    /**
     * A typed time of day: "15:30", "9:05", "1530", "930", "9", "15", "3pm", "3:30 pm", "12am"; null when it isn't one.
     */
    fun parseTime(text: String): LocalTime? {
        val t = text.trim().lowercase(Locale.ENGLISH).replace(" ", "").replace('.', ':')
        val m = TIME_INPUT.matchEntire(t) ?: return null
        val digits = m.groupValues[1]
        val meridiem = m.groupValues[2]
        val (hour, minute) = when {
            digits.contains(':') -> digits.substringBefore(':').toInt() to digits.substringAfter(':').toInt()
            digits.length <= 2 -> digits.toInt() to 0
            else -> digits.dropLast(2).toInt() to digits.takeLast(2).toInt()
        }
        if (minute !in 0..59) return null
        return when (meridiem) {
            "" -> if (hour in 0..23) LocalTime.of(hour, minute) else null
            else -> {
                if (hour !in 1..12) return null
                val h = (hour % 12) + if (meridiem == "pm") 12 else 0
                LocalTime.of(h, minute)
            }
        }
    }

    /**
     * A typed duration in minutes: "90", "45m", "45 min", "2h", "1.5h", "1h30", "1h 30m", "1:30"; null when it isn't
     * one or isn't above zero.
     */
    fun parseDuration(text: String): Int? {
        val t = text.trim().lowercase(Locale.ENGLISH).replace(" ", "")
        if (t.isEmpty()) return null
        val minutes = DURATION_MINUTES.matchEntire(t)?.let { it.groupValues[1].toDoubleOrNull() }
            ?: DURATION_HOURS.matchEntire(t)?.let { m ->
                val hours = m.groupValues[1].toDoubleOrNull() ?: return null
                hours * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
            }
            ?: DURATION_CLOCK.matchEntire(t)?.let { m -> m.groupValues[1].toInt() * 60.0 + m.groupValues[2].toInt() }
            ?: return null
        return Math.round(minutes).toInt().takeIf { it > 0 }
    }

    /** "Every day", "Every 3 days", "Every week", "Every 2 weeks", "Mon, Wed, Fri". */
    fun repeat(repeat: TaskRepeat): String = when (repeat.kind) {
        RepeatKind.DAYS -> if (repeat.interval <= 1) "Every day" else "Every ${repeat.interval} days"
        RepeatKind.WEEKS -> if (repeat.interval <= 1) "Every week" else "Every ${repeat.interval} weeks"
        RepeatKind.WEEKDAYS -> if (repeat.weekdays.size == 7) {
            "Every day"
        } else {
            repeat.weekdays.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
        }
    }

    /** How a reminder reads: "At the time" or "On the day", "15 minutes before", "1 hour before", "1 day before". */
    fun reminder(minutesBefore: Int, timed: Boolean): String = when {
        minutesBefore == 0 -> if (timed) "At the time" else "On the day"
        minutesBefore % 1440 == 0 -> plural(minutesBefore / 1440, "day") + " before"
        minutesBefore % 60 == 0 -> plural(minutesBefore / 60, "hour") + " before"
        else -> plural(minutesBefore, "minute") + " before"
    }

    private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"

    private val TIME_INPUT = Regex("""^(\d{1,2}:\d{2}|\d{1,4})(am|pm)?$""")
    private val DURATION_MINUTES = Regex("""^(\d+)(?:m|min|mins|minute|minutes)?$""")
    private val DURATION_HOURS = Regex("""^(\d+(?:\.\d+)?)(?:h|hr|hrs|hour|hours)(?:(\d{1,2})(?:m|min|mins)?)?$""")
    private val DURATION_CLOCK = Regex("""^(\d{1,3}):(\d{2})$""")

    /** "Due today at 15:00", "Due tomorrow", "Due Friday", "Due Fri 6 Nov", "Overdue by 2 days", "Overdue since yesterday". */
    fun due(date: LocalDate, time: LocalTime?, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, date)
        if (days < 0) return if (days == -1L) "Overdue since yesterday" else "Overdue by ${-days} days"
        val day = when (days) {
            0L -> "today"
            1L -> "tomorrow"
            in 2L..6L -> date.format(WEEKDAY)
            else -> date(date, today)
        }
        return if (time == null) "Due $day" else "Due $day at ${time(time)}"
    }
}
