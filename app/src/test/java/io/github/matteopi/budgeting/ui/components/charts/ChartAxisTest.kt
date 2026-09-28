package io.github.matteopi.budgeting.ui.components.charts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartAxisTest {

    @Test
    fun ticks_roundsToNiceNumbers() {
        assertEquals(listOf(0.0, 50.0, 100.0), ChartAxis.ticks(0.0, 100.0, targetCount = 4))
    }

    @Test
    fun ticks_coversTheWholeRange() {
        val ticks = ChartAxis.ticks(12.0, 187.0, targetCount = 4)
        assertTrue(ticks.first() <= 12.0)
        assertTrue(ticks.last() >= 187.0)
        // Evenly spaced.
        val step = ticks[1] - ticks[0]
        for (i in 1 until ticks.size) {
            assertEquals(step, ticks[i] - ticks[i - 1], 1e-9)
        }
    }

    @Test
    fun ticks_emptyWhenRangeIsDegenerate() {
        assertEquals(emptyList(), ChartAxis.ticks(10.0, 10.0))
        assertEquals(emptyList(), ChartAxis.ticks(10.0, 5.0))
    }

    @Test
    fun moneyTicks_areWholePounds() {
        val ticks = ChartAxis.moneyTicks(Money.ZERO, Money.pounds(100))
        assertEquals(listOf(Money.ZERO, Money.pounds(50), Money.pounds(100)), ticks)
    }

    @Test
    fun monthTicks_oneFirstOfMonthPerOverlappingMonth() {
        val start = LocalDate.of(2026, 9, 28)
        val end = LocalDate.of(2026, 12, 5)
        val ticks = ChartAxis.monthTicks(start, end)
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 28), // clamped to start, since 1 Sep is before it
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 12, 1),
            ),
            ticks,
        )
    }

    @Test
    fun monthTicks_emptyWhenEndBeforeStart() {
        assertEquals(emptyList(), ChartAxis.monthTicks(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1)))
    }
}
