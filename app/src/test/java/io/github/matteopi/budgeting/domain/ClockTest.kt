package io.github.matteopi.budgeting.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ClockTest {
    private val day1 = LocalDate.of(2026, 9, 28)
    private val day2 = LocalDate.of(2026, 9, 29)

    @Test
    fun `waits for midnight in one go only when midnight is closer than the poll interval`() {
        assertEquals(60_000L, DateWatch.nextCheckDelayMillis(8 * 60 * 60 * 1000L))
        assertEquals(60_000L, DateWatch.nextCheckDelayMillis(60_000L))
        assertEquals(30_000L, DateWatch.nextCheckDelayMillis(30_000L))
    }

    @Test
    fun `never asks for a delay shorter than the minimum, even at or past midnight`() {
        assertEquals(DateWatch.MIN_DELAY_MILLIS, DateWatch.nextCheckDelayMillis(0L))
        assertEquals(DateWatch.MIN_DELAY_MILLIS, DateWatch.nextCheckDelayMillis(-5_000L))
        assertEquals(DateWatch.MIN_DELAY_MILLIS, DateWatch.nextCheckDelayMillis(10L))
    }

    @Test
    fun `a different date in either direction counts as a change`() {
        assertTrue(DateWatch.hasChanged(day1, day2))
        assertTrue(DateWatch.hasChanged(day2, day1))
        assertFalse(DateWatch.hasChanged(day1, day1))
    }

    @Test
    fun `millis until midnight is measured to the next local midnight`() {
        val zone: ZoneId = ZoneOffset.UTC
        val nineAm = day1.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 60 * 60 * 1000L
        assertEquals(15 * 60 * 60 * 1000L, DateWatch.millisUntilNextMidnight(day1, nineAm, zone))
    }

    @Test
    fun `emits today immediately and then only when the date changes`() = runTest {
        var date = day1
        val clock = Clock(dateSource = { date }, millisToMidnightSource = { 8 * 60 * 60 * 1000L })
        val emitted = mutableListOf<LocalDate>()
        val job = launch { clock.todayFlow.toList(emitted) }

        runCurrent()
        assertEquals(listOf(day1), emitted)

        // Several polls with no date change emit nothing.
        advanceTimeBy(3 * 60_000L + 1)
        runCurrent()
        assertEquals(listOf(day1), emitted)

        // The date rolls over: it is noticed within one poll interval.
        date = day2
        advanceTimeBy(60_000L + 1)
        runCurrent()
        assertEquals(listOf(day1, day2), emitted)

        // And it is not emitted again while it stays the same.
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(listOf(day1, day2), emitted)

        job.cancel()
    }

    @Test
    fun `every new collector starts from the current date`() = runTest {
        var date = day1
        val clock = Clock(dateSource = { date }, millisToMidnightSource = { 8 * 60 * 60 * 1000L })

        val first = mutableListOf<LocalDate>()
        val firstJob = launch { clock.todayFlow.toList(first) }
        runCurrent()
        firstJob.cancel()

        date = day2
        val second = mutableListOf<LocalDate>()
        val secondJob = launch { clock.todayFlow.toList(second) }
        runCurrent()
        secondJob.cancel()

        assertEquals(listOf(day1), first)
        assertEquals(listOf(day2), second)
    }
}
