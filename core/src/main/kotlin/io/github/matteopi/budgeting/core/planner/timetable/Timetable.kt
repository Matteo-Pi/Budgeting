package io.github.matteopi.budgeting.core.planner.timetable

import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Teaching weeks: week 1 is the Monday-to-Sunday week a term starts in (docs/PLAN.md §13.4). */
object TeachingWeeks {
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The teaching week of [date] in [term] (1 for the week the term starts in); [date] should be inside the term. */
    fun number(date: LocalDate, term: TermPeriod): Int =
        (ChronoUnit.DAYS.between(weekStart(term.startDate), weekStart(date)) / 7).toInt() + 1

    /** How many teaching weeks [term] has. */
    fun count(term: TermPeriod): Int = number(term.endDate, term)
}

/** The outcome of reading a list of weeks such as "1-5, 7–11". */
sealed interface WeekListResult {
    data class Ok(val weeks: Set<Int>) : WeekListResult
    data class Error(val message: String) : WeekListResult
}

/** Lists of teaching weeks as the user types them: "1-5, 7–11", "1 3 5", "2 to 6". */
object WeekList {
    const val MAX_WEEK = 60

    private val RANGE = Regex("""^(\d{1,2})\s*(?:-|–|—|to)\s*(\d{1,2})$""")

    fun parse(text: String): WeekListResult {
        val parts = text.split(',', ';').flatMap { part ->
            val p = part.trim()
            // "1 3 5" is three weeks, but "2 to 6" and "2 - 6" are ranges.
            if (RANGE.matches(p) || !p.contains(' ')) listOf(p) else p.split(Regex("\\s+"))
        }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return WeekListResult.Error("Type the weeks, such as 1-5, 7-11.")
        val weeks = sortedSetOf<Int>()
        for (part in parts) {
            val range = RANGE.matchEntire(part)
            val (from, to) = when {
                range != null -> range.groupValues[1].toInt() to range.groupValues[2].toInt()
                part.all { it.isDigit() } && part.length <= 2 -> part.toInt() to part.toInt()
                else -> return WeekListResult.Error("\"$part\" isn't a week or a range of weeks.")
            }
            if (from > to) return WeekListResult.Error("\"$part\" goes backwards.")
            if (from < 1 || to > MAX_WEEK) return WeekListResult.Error("Weeks are numbered from 1 to $MAX_WEEK.")
            for (w in from..to) weeks += w
        }
        return WeekListResult.Ok(weeks)
    }

    /** The shortest form of [weeks]: "1–5, 7–11", "3", "1, 3, 5". */
    fun format(weeks: Set<Int>): String {
        val sorted = weeks.sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (w in sorted.drop(1) + Int.MIN_VALUE) {
            if (w == prev + 1) {
                prev = w
                continue
            }
            parts += when {
                prev == start -> "$start"
                prev == start + 1 -> "$start, $prev"
                else -> "$start–$prev"
            }
            start = w
            prev = w
        }
        return parts.joinToString(", ")
    }
}

/** Expanding the timetable into dated classes (docs/PLAN.md §13.4). */
object Timetable {

    /**
     * Whether [entry] is on [date] (ignoring exceptions):
     * - [WeekPattern.Once] on its own date only;
     * - weekly patterns on the entry's weekday, inside a term (the entry's own term, if it has one), in the right teaching
     *   week; outside terms only [WeekPattern.EveryWeek] entries marked [TimetableEntry.inHolidays].
     * With no term dates at all, weekly entries are every week and the other patterns can't be placed.
     */
    fun isOn(entry: TimetableEntry, date: LocalDate, terms: List<TermPeriod>): Boolean {
        val pattern = entry.pattern
        if (pattern is WeekPattern.Once) return pattern.date == date
        if (date.dayOfWeek != entry.weekday) return false
        if (terms.isEmpty()) return pattern == WeekPattern.EveryWeek
        val term = Regimes.termContaining(date, terms)
            ?: return entry.inHolidays && pattern == WeekPattern.EveryWeek && entry.termId == null
        if (entry.termId != null && entry.termId != term.id) return false
        val week = TeachingWeeks.number(date, term)
        return when (pattern) {
            WeekPattern.EveryWeek -> true
            is WeekPattern.TeachingWeeks -> week in pattern.weeks
            WeekPattern.OddWeeks -> week % 2 == 1
            WeekPattern.EvenWeeks -> week % 2 == 0
        }
    }

    /**
     * The classes from [from] to [to] (inclusive), sorted by start: each live entry on each date it is on, with that
     * date's exception applied. Cancelled ones are left out unless [includeCancelled] (the timetable shows them greyed,
     * to restore). Costs days × entries.
     */
    fun occurrences(
        entries: List<TimetableEntry>,
        exceptions: List<EntryException>,
        terms: List<TermPeriod>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        includeCancelled: Boolean = false,
    ): List<Occurrence> {
        val live = entries.filter { !it.archived }
        if (live.isEmpty() || to.isBefore(from)) return emptyList()
        val byKey = exceptions.associateBy { it.entryId to it.date }
        val result = ArrayList<Occurrence>()
        var date = from
        while (!date.isAfter(to)) {
            for (entry in live) {
                if (!isOn(entry, date, terms)) continue
                val exception = byKey[entry.id to date]
                val cancelled = exception?.cancelled == true
                if (cancelled && !includeCancelled) continue
                val startTime = exception?.start ?: entry.start
                val endTime = exception?.end ?: entry.end
                val start = ZonedDateTime.of(date, startTime, zone).toInstant()
                // An end at or before the start (a class past midnight) ends the next day.
                val endDate = if (endTime.isAfter(startTime)) date else date.plusDays(1)
                result += Occurrence(
                    entryId = entry.id,
                    date = date,
                    start = start,
                    end = ZonedDateTime.of(endDate, endTime, zone).toInstant(),
                    startTime = startTime,
                    endTime = endTime,
                    title = entry.title,
                    kind = entry.kind,
                    moduleId = entry.moduleId,
                    location = entry.location,
                    room = exception?.room ?: entry.room,
                    colorArgb = entry.colorArgb,
                    changed = exception != null && !cancelled,
                    cancelled = cancelled,
                )
            }
            date = date.plusDays(1)
        }
        result.sortWith(compareBy<Occurrence> { it.start }.thenBy { it.end }.thenBy { it.entryId })
        return result
    }

    /** The dates of [entry] from [from] to [to], ignoring exceptions: what the editor previews for chosen weeks. */
    fun dates(entry: TimetableEntry, terms: List<TermPeriod>, from: LocalDate, to: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var date = from
        while (!date.isAfter(to)) {
            if (isOn(entry, date, terms)) dates += date
            date = date.plusDays(1)
        }
        return dates
    }
}
