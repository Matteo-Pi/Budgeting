package io.github.matteopi.budgeting.work

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkSchedulerTest {
    @Test
    fun `delay to later today when before 3am`() {
        val now = LocalDateTime.of(2026, 9, 28, 1, 0)
        val delay = WorkScheduler.initialDelayMillis(now)
        assertEquals(2 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun `delay to tomorrow when exactly 3am`() {
        val now = LocalDateTime.of(2026, 9, 28, 3, 0)
        val delay = WorkScheduler.initialDelayMillis(now)
        assertEquals(24 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun `delay to tomorrow when after 3am`() {
        val now = LocalDateTime.of(2026, 9, 28, 20, 0)
        val delay = WorkScheduler.initialDelayMillis(now)
        assertEquals(7 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun `delay just before 3am is short`() {
        val now = LocalDateTime.of(2026, 9, 28, 2, 59)
        val delay = WorkScheduler.initialDelayMillis(now)
        assertEquals(60 * 1000L, delay)
    }
}
