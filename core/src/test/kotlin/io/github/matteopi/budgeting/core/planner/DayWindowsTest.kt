package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.DayMode
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.Intervals
import io.github.matteopi.budgeting.core.planner.day.Snap
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The day's windows, intervals and the 15-minute grid (docs/PLAN.md §13.2 checkpoints), in Europe/London. */
class DayWindowsTest {

    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Instant = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant()
    private val fixed = DaySettings(mode = DayMode.FIXED)
    private val lateNights = DaySettings(wakeMinutes = List(7) { 8 * 60 }, sleepMinutes = List(7) { 60 })

    @Test
    fun `a fixed day runs cut-off to cut-off, 23 or 25 hours when the clocks change`() {
        assertEquals(23, DayWindows.recordWindow(LocalDate.of(2026, 3, 28), fixed, zone).minutes / 60)
        assertEquals(25, DayWindows.recordWindow(LocalDate.of(2026, 10, 24), fixed, zone).minutes / 60)
        assertEquals(24, DayWindows.recordWindow(LocalDate.of(2026, 10, 20), fixed, zone).minutes / 60)
        val window = DayWindows.recordWindow(LocalDate.of(2026, 10, 20), fixed, zone)
        assertEquals(at(2026, 10, 20, 4), window.start)
        assertEquals(at(2026, 10, 21, 4), window.end)
    }

    @Test
    fun `wake to sleep runs past midnight when sleep is earlier than wake`() {
        val window = DayWindows.recordWindow(LocalDate.of(2026, 10, 23), lateNights, zone)
        assertEquals(at(2026, 10, 23, 8), window.start)
        assertEquals(at(2026, 10, 24, 1), window.end)
        assertEquals(17 * 60L, window.minutes)
        val early = DaySettings(sleepMinutes = List(7) { 22 * 60 + 30 })
        assertEquals(at(2026, 10, 23, 22, 30), DayWindows.recordWindow(LocalDate.of(2026, 10, 23), early, zone).end)
        assertEquals(16 * 60L, DayWindows.wakingWindow(LocalDate.of(2026, 10, 23), DaySettings(), zone).minutes, "08:00 to midnight")
    }

    @Test
    fun `the current day holds a late night, then the coming day before it starts`() {
        assertEquals(LocalDate.of(2026, 10, 23), DayWindows.currentDay(at(2026, 10, 24, 0, 30), lateNights, zone))
        assertEquals(LocalDate.of(2026, 10, 24), DayWindows.currentDay(at(2026, 10, 24, 2), lateNights, zone))
        assertEquals(LocalDate.of(2026, 10, 24), DayWindows.currentDay(at(2026, 10, 24, 9), lateNights, zone))
        assertEquals(LocalDate.of(2026, 10, 19), DayWindows.currentDay(at(2026, 10, 20, 3, 59), fixed, zone))
        assertEquals(LocalDate.of(2026, 10, 20), DayWindows.currentDay(at(2026, 10, 20, 4), fixed, zone))
        assertEquals(LocalDate.of(2026, 10, 20), DayWindows.currentDay(at(2026, 10, 20, 23, 50), DaySettings(sleepMinutes = List(7) { 23 * 60 }), zone))
    }

    @Test
    fun `a sleep time in the spring-forward gap moves on by the gap`() {
        val settings = DaySettings(sleepMinutes = List(7) { 90 })
        assertEquals(
            ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 2, 30), zone).toInstant(),
            DayWindows.recordWindow(LocalDate.of(2026, 3, 28), settings, zone).end,
        )
        assertEquals(LocalTime.of(2, 30), DayWindows.recordWindow(LocalDate.of(2026, 3, 28), settings, zone).end.atZone(zone).toLocalTime())
    }

    @Test
    fun `wrong settings fall back to the defaults`() {
        val bad = DaySettings(cutoffMinute = 9999, wakeMinutes = listOf(1, 2), sleepMinutes = List(7) { -5 })
        assertEquals(DaySettings(), bad.sanitized())
    }

    @Test
    fun `intervals merge, subtract and total`() {
        fun iv(h1: Int, m1: Int, h2: Int, m2: Int) = Interval(at(2026, 10, 20, h1, m1), at(2026, 10, 20, h2, m2))
        assertEquals(listOf(iv(9, 0, 11, 0), iv(12, 0, 13, 0)), Intervals.merge(listOf(iv(10, 0, 11, 0), iv(9, 0, 10, 0), iv(12, 0, 13, 0), iv(9, 30, 9, 45))))
        assertEquals(
            listOf(iv(8, 0, 9, 0), iv(10, 0, 12, 0), iv(13, 0, 14, 0)),
            Intervals.subtract(iv(8, 0, 14, 0), listOf(iv(9, 0, 10, 0), iv(12, 0, 13, 0), iv(15, 0, 16, 0))),
        )
        assertEquals(emptyList(), Intervals.subtract(iv(9, 0, 10, 0), listOf(iv(8, 0, 11, 0))))
        assertEquals(120, Intervals.totalMinutes(listOf(iv(9, 0, 10, 30), iv(10, 0, 11, 0))))
        assertEquals(30, Intervals.overlapMinutes(iv(9, 0, 10, 0), listOf(iv(9, 30, 11, 0))))
        assertNull(iv(9, 0, 10, 0).intersect(iv(10, 0, 11, 0)), "touching is not overlapping")
        assertFalse(iv(9, 0, 10, 0).overlaps(iv(10, 0, 11, 0)))
        assertTrue(iv(9, 0, 10, 0).isEmpty.not() && iv(10, 0, 9, 0).isEmpty)
    }

    @Test
    fun `drags snap to the local quarter hour, also across a clock change`() {
        assertEquals(at(2026, 10, 20, 14, 0), Snap.snap(at(2026, 10, 20, 14, 7), zone))
        assertEquals(at(2026, 10, 20, 14, 15), Snap.snap(at(2026, 10, 20, 14, 8), zone))
        assertEquals(at(2026, 10, 20, 14, 15), Snap.snap(at(2026, 10, 20, 14, 7).plusSeconds(30), zone), "half-way goes later")
        assertEquals(at(2026, 10, 20, 14, 0), Snap.snap(at(2026, 10, 20, 14, 7).minusSeconds(31), zone))
        assertEquals(at(2026, 10, 21, 0, 0), Snap.snap(at(2026, 10, 20, 23, 55), zone))
        // 25 Oct: 01:00–02:00 happens twice; the grid is the local clock's.
        val second = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 1, 10), zone).withLaterOffsetAtOverlap().toInstant()
        assertEquals(second.plusSeconds(5 * 60), Snap.snap(second, zone), "the second 01:10 snaps to the second 01:15")
    }
}
