package io.github.matteopi.budgeting.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The current date/time. Everything that needs "today" goes through this, not `LocalDate.now()`.
 *
 * [dateSource] and [millisToMidnightSource] are injectable only so the date-change logic can be
 * tested with a fake clock; the app always uses the defaults.
 */
class Clock(
    private val dateSource: () -> LocalDate = { LocalDate.now() },
    private val millisToMidnightSource: () -> Long = { DateWatch.millisUntilNextMidnight() },
) {
    fun today(): LocalDate = LocalDate.now()

    fun nowMillis(): Long = System.currentTimeMillis()

    /** The phone's time zone, read on every call so a change of zone applies straight away (docs/PLAN.md §13.2). */
    fun zone(): ZoneId = ZoneId.systemDefault()

    /**
     * Emits now, then at each minute boundary, only while collected (docs/PLAN.md §13.2). Like [todayFlow] it never
     * waits more than a minute at a time, so deep sleep can't leave it far behind.
     */
    val minuteFlow: Flow<Instant> = flow {
        while (true) {
            val now = nowMillis()
            emit(Instant.ofEpochMilli(now))
            delay((MINUTE_MILLIS - now % MINUTE_MILLIS).coerceIn(DateWatch.MIN_DELAY_MILLIS, MINUTE_MILLIS))
        }
    }

    /**
     * Emits the current date immediately, then again whenever the date changes.
     *
     * It re-reads the date at least every [DateWatch.MAX_POLL_MILLIS] instead of sleeping until
     * midnight in one long `delay`: `delay` runs on the uptime clock, which stops in deep sleep, so
     * a single long delay can fire hours late and leave the forecast working out "today" for
     * yesterday (transactions dated today then look like the future and the balance reads £0).
     */
    val todayFlow: Flow<LocalDate> = flow {
        var last = dateSource()
        emit(last)
        while (true) {
            delay(DateWatch.nextCheckDelayMillis(millisToMidnightSource()))
            val now = dateSource()
            if (DateWatch.hasChanged(last, now)) {
                last = now
                emit(now)
            }
        }
    }
}

private const val MINUTE_MILLIS = 60_000L

/** The pure timing rules behind [Clock.todayFlow], split out so they can be unit-tested. */
object DateWatch {
    /** The date is re-checked at least this often. */
    const val MAX_POLL_MILLIS = 60_000L

    /** Never spin: even right at midnight the next check is at least this far away. */
    const val MIN_DELAY_MILLIS = 250L

    /** How long to wait before the next look at the date: the sooner of midnight and [MAX_POLL_MILLIS]. */
    fun nextCheckDelayMillis(millisToMidnight: Long): Long =
        minOf(millisToMidnight, MAX_POLL_MILLIS).coerceAtLeast(MIN_DELAY_MILLIS)

    /** True when [now] is a different date from [last] (either direction: clocks and time zones can move back). */
    fun hasChanged(last: LocalDate, now: LocalDate): Boolean = now != last

    /** Milliseconds from now to the next local midnight. */
    fun millisUntilNextMidnight(
        today: LocalDate = LocalDate.now(),
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - nowMillis
}
