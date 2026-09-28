package io.github.matteopi.budgeting.domain

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.testing.ForecastFixtures
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class ForecastStateTest {
    private val input = ForecastFixtures.input()
    private val result = ForecastFixtures.result(input)

    private fun ready(millis: Long = 1_000L) = ForecastState.Ready(input, result, millis)

    @Test
    fun `a successful run is ready with the input it was computed from`() {
        val state = ForecastStates.compute(input, lastGood = null, nowMillis = { 42L }, run = { result })
        val ready = assertIs<ForecastState.Ready>(state)
        assertSame(input, ready.input)
        assertSame(result, ready.result)
        assertEquals(42L, ready.computedAtMillis)
    }

    @Test
    fun `a failing run becomes failed, keeps the last good state and reports the error`() {
        val good = ready()
        val logged = mutableListOf<Throwable>()
        val state = ForecastStates.compute(
            input = input,
            lastGood = good,
            nowMillis = { 0L },
            run = { throw IllegalStateException("boom") },
            onError = { logged += it },
        )
        val failed = assertIs<ForecastState.Failed>(state)
        assertEquals("boom", failed.message)
        assertSame(good, failed.lastGood)
        assertSame(input, failed.input)
        assertEquals(1, logged.size)
    }

    @Test
    fun `a failure without a message still says what went wrong`() {
        val state = ForecastStates.compute(input, lastGood = null, nowMillis = { 0L }, run = { throw IllegalArgumentException() })
        val failed = assertIs<ForecastState.Failed>(state)
        assertEquals("IllegalArgumentException", failed.message)
        assertNull(failed.lastGood)
    }

    @Test
    fun `cancellation is never swallowed`() {
        assertFailsWith<CancellationException> {
            ForecastStates.compute(input, lastGood = null, nowMillis = { 0L }, run = { throw CancellationException("stop") })
        }
    }

    @Test
    fun `loading has no snapshot`() {
        assertNull(ForecastState.Loading.snapshot())
    }

    @Test
    fun `a ready state's snapshot pairs the input with the result computed from it`() {
        val snapshot = ready(5_000L).snapshot()!!
        assertSame(input, snapshot.input)
        assertSame(result, snapshot.result)
        assertEquals(5_000L, snapshot.computedAtMillis)
        assertNull(snapshot.error)
    }

    @Test
    fun `a failed state with a last good result shows that older pair together, plus the error`() {
        val newerInput = ForecastFixtures.input(transactions = ForecastFixtures.transactions(current = 5_000))
        val failed = ForecastState.Failed(newerInput, "boom", lastGood = ready(7L))
        val snapshot = failed.snapshot()!!
        assertSame(input, snapshot.input)
        assertSame(result, snapshot.result)
        assertEquals(7L, snapshot.computedAtMillis)
        assertEquals("boom", snapshot.error)
    }

    @Test
    fun `a failed state with nothing good still exposes its input but no result`() {
        val snapshot = ForecastState.Failed(input, "boom", lastGood = null).snapshot()!!
        assertSame(input, snapshot.input)
        assertNull(snapshot.result)
        assertNull(snapshot.computedAtMillis)
        assertEquals("boom", snapshot.error)
    }

    @Test
    fun `diagnostics name the time, the date, the accounts and the total the forecast believes`() {
        val millis = ForecastFixtures.today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 4 * 60_000L + 12_000L
        val text = ForecastDiagnostics.describe(ready(millis), ZoneOffset.UTC)
        // Three accounts, two of them in the pool: £1,000 + £500 = £1,500.
        assertEquals(
            "Forecast updated 00:04:12 for Mon 28 Sep · 3 accounts (2 in safe-to-spend) · total £1,500.00",
            text,
        )
        assertEquals(Money.pounds(1_500), result.poolBalanceNow)
    }

    @Test
    fun `diagnostics leave out the pool split when every account is in it`() {
        val poolOnly = ForecastFixtures.input(accounts = ForecastFixtures.accounts().take(2))
        val text = ForecastDiagnostics.describe(
            ForecastState.Ready(poolOnly, ForecastFixtures.result(poolOnly), 0L),
            ZoneOffset.UTC,
        )
        assertTrue(text.contains("· 2 accounts · total £1,500.00"), text)
    }

    @Test
    fun `diagnostics report a failure and the update still being shown`() {
        val failed = ForecastState.Failed(input, "boom", lastGood = ready(4 * 60_000L + 12_000L))
        assertEquals(
            "Forecast failed: boom (showing the update from 00:04:12)",
            ForecastDiagnostics.describe(failed, ZoneOffset.UTC),
        )
        assertEquals(
            "Forecast failed: boom",
            ForecastDiagnostics.describe(ForecastState.Failed(input, "boom", null), ZoneOffset.UTC),
        )
    }

    @Test
    fun `diagnostics while loading`() {
        assertEquals("Forecast not calculated yet", ForecastDiagnostics.describe(ForecastState.Loading))
    }
}
