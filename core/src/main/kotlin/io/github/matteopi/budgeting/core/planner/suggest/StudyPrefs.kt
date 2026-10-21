package io.github.matteopi.budgeting.core.planner.suggest

import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.serialization.Serializable

/** A time of day from [startMinute] to [endMinute] (minutes after midnight; an end of 1440 is midnight). */
@Serializable
data class TimeWindow(val startMinute: Int, val endMinute: Int) {
    val isValid: Boolean get() = startMinute in 0 until 24 * 60 && endMinute in 1..24 * 60 && endMinute > startMinute

    operator fun contains(time: LocalTime): Boolean = (time.hour * 60 + time.minute).let { it in startMinute until endMinute }

    companion object {
        fun of(start: LocalTime, end: LocalTime): TimeWindow =
            TimeWindow(start.hour * 60 + start.minute, if (end == LocalTime.MIDNIGHT) 24 * 60 else end.hour * 60 + end.minute)
    }
}

/**
 * How study is suggested (docs/PLAN.md §12.7, §13.6): [on] everywhere; [preferred] times score higher and [avoided]
 * ones are left out; [weeklyDaysOff] (ISO day numbers, Monday 1) get nothing; blocks are [longestMinutes] at most, a
 * [breakMinutes] break after each, and [shortestMinutes] at least (a demand's last piece may be made up to it); at most
 * [dailyCapMinutes] a day and [weeklyCapMinutes] a week (null: no weekly cap); deadlines are finished
 * [finishEarlyDays] early; [learnFromRecord] uses the focus profile; revision starts [revisionStartDays] before an exam
 * with [revisionBlockMinutes] blocks; [offModules] get no suggestions. Stored as JSON; a missing field takes its
 * default.
 */
@Serializable
data class StudyPrefs(
    val on: Boolean = true,
    val preferred: List<TimeWindow> = emptyList(),
    val avoided: List<TimeWindow> = emptyList(),
    val weeklyDaysOff: List<Int> = emptyList(),
    val longestMinutes: Int = 90,
    val shortestMinutes: Int = 30,
    val breakMinutes: Int = 15,
    val dailyCapMinutes: Int = 240,
    val weeklyCapMinutes: Int? = null,
    val finishEarlyDays: Int = 1,
    val learnFromRecord: Boolean = true,
    val revisionStartDays: Int = 21,
    val revisionBlockMinutes: Int = 45,
    val offModules: List<Long> = emptyList(),
) {
    val daysOff: Set<DayOfWeek> get() = weeklyDaysOff.filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet()

    /** The same preferences with wrong values (a block shorter than 15 minutes, a negative break) set back to the defaults. */
    fun sanitized(): StudyPrefs {
        val d = StudyPrefs()
        val shortest = shortestMinutes.takeIf { it in 15..240 } ?: d.shortestMinutes
        return copy(
            preferred = preferred.filter { it.isValid },
            avoided = avoided.filter { it.isValid },
            longestMinutes = longestMinutes.takeIf { it in shortest..480 } ?: maxOf(shortest, d.longestMinutes),
            shortestMinutes = shortest,
            breakMinutes = breakMinutes.takeIf { it in 0..120 } ?: d.breakMinutes,
            dailyCapMinutes = dailyCapMinutes.takeIf { it in 15..24 * 60 } ?: d.dailyCapMinutes,
            weeklyCapMinutes = weeklyCapMinutes?.takeIf { it in 15..7 * 24 * 60 },
            finishEarlyDays = finishEarlyDays.takeIf { it in 0..14 } ?: d.finishEarlyDays,
            revisionStartDays = revisionStartDays.takeIf { it in 1..120 } ?: d.revisionStartDays,
            revisionBlockMinutes = revisionBlockMinutes.takeIf { it in 15..240 } ?: d.revisionBlockMinutes,
        )
    }
}
