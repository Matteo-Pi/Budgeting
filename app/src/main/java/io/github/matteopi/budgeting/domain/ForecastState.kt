package io.github.matteopi.budgeting.domain

import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * The forecast as the UI sees it. A [Ready] state carries the [ForecastInput] and the
 * [ForecastResult] that was computed *from that very input*, so a screen that draws both (account
 * rows next to "Your money", say) can never mix an old result with newer data.
 */
sealed interface ForecastState {
    /** Nothing has been calculated yet (app just started, or no data has arrived). */
    data object Loading : ForecastState

    data class Ready(
        val input: ForecastInput,
        val result: ForecastResult,
        /** Wall-clock time the calculation finished, for the diagnostics line in Settings. */
        val computedAtMillis: Long,
    ) : ForecastState

    /**
     * The engine threw for [input]. [lastGood] is the last successful calculation (from an older
     * input), if there has been one, so screens can keep showing numbers next to an error.
     */
    data class Failed(
        val input: ForecastInput,
        val message: String,
        val lastGood: Ready?,
    ) : ForecastState
}

/**
 * What a screen should draw for a [ForecastState]: an [input] and the [result] computed from that
 * same input (null only when the engine failed and there is no earlier success). [error] is set
 * when the latest run failed, in which case [input]/[result] are the last good pair.
 */
data class ForecastSnapshot(
    val input: ForecastInput,
    val result: ForecastResult?,
    val computedAtMillis: Long?,
    val error: String?,
)

/** The [ForecastSnapshot] to draw, or null while still [ForecastState.Loading]. */
fun ForecastState.snapshot(): ForecastSnapshot? = when (this) {
    ForecastState.Loading -> null
    is ForecastState.Ready -> ForecastSnapshot(input, result, computedAtMillis, error = null)
    is ForecastState.Failed -> {
        val good = lastGood
        if (good != null) {
            ForecastSnapshot(good.input, good.result, good.computedAtMillis, error = message)
        } else {
            ForecastSnapshot(input, result = null, computedAtMillis = null, error = message)
        }
    }
}

/** Runs the engine for [ForecastState] values; separate from [ForecastService] so it is testable without Android. */
object ForecastStates {
    /**
     * [run] applied to [input] as a [ForecastState.Ready]; if it throws, a [ForecastState.Failed]
     * keeping [lastGood]. Coroutine cancellation is never swallowed.
     */
    fun compute(
        input: ForecastInput,
        lastGood: ForecastState.Ready?,
        nowMillis: () -> Long,
        run: (ForecastInput) -> ForecastResult,
        onError: (Throwable) -> Unit = {},
    ): ForecastState = try {
        val result = run(input)
        ForecastState.Ready(input, result, nowMillis())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        ForecastState.Failed(input, e.message ?: e.javaClass.simpleName, lastGood)
    }
}

/** The one-line "what does the forecast think?" text for Settings > About. */
object ForecastDiagnostics {
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /**
     * e.g. "Forecast updated 00:04:12 for Tue 29 Sep · 3 accounts · total £7,479.30". The total is
     * what the *forecast* believes the pool holds, so it can be compared with the account rows.
     */
    fun describe(state: ForecastState, zone: ZoneId = ZoneId.systemDefault()): String = when (state) {
        ForecastState.Loading -> "Forecast not calculated yet"
        is ForecastState.Ready -> describeReady(state, zone)
        is ForecastState.Failed -> {
            val good = state.lastGood
            val tail = if (good != null) " (showing the update from ${timeOf(good.computedAtMillis, zone)})" else ""
            "Forecast failed: ${state.message}$tail"
        }
    }

    private fun describeReady(state: ForecastState.Ready, zone: ZoneId): String {
        val live = state.input.accounts.count { !it.archived }
        val pool = Balances.pool(state.input.accounts).size
        val accounts = when {
            live == 1 -> "1 account"
            pool == live -> "$live accounts"
            else -> "$live accounts ($pool in safe-to-spend)"
        }
        val forDate: LocalDate = state.result.today
        return "Forecast updated ${timeOf(state.computedAtMillis, zone)} for ${forDate.format(DATE_FORMAT)}" +
            " · $accounts · total ${state.result.poolBalanceNow.format()}"
    }

    private fun timeOf(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(TIME_FORMAT)
}
