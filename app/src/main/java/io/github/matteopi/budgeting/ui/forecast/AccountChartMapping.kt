package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.forecast.AccountForecast
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.ui.components.charts.ForecastChartDay
import io.github.matteopi.budgeting.ui.components.charts.ForecastChartLine
import java.time.LocalDate

/**
 * Everything [io.github.matteopi.budgeting.ui.components.charts.ForecastChart] needs for the
 * chosen [AccountView], already in display terms.
 */
data class ForecastChartData(
    /** The view actually shown: the requested one, or [AccountView.Total] if it could not be honoured. */
    val view: AccountView,
    /** The date axis and term shading; for [AccountView.Total] and [AccountView.Single] also the band and expected line. */
    val days: List<ForecastChartDay>,
    /** One expected-balance line per account for [AccountView.Each]; null for the other views. */
    val lines: List<ForecastChartLine>?,
    /** Height of the buffer line above the floor; zero (no line) for anything but the pool total, which alone has a buffer. */
    val buffer: Money,
    /** Only the pool total has a tier-1 baseline. */
    val hasTier1: Boolean,
)

/**
 * Pure mapping from the engine's per-account forecasts ([ForecastResult.accountForecasts]) to
 * chart data, so the per-account views are unit-testable on the plain JVM. Each account is
 * converted to display terms with its *own* overdraft ([AccountForecast.overdraft]); the floor
 * line is at display £0 in every view, because display £0 is the floor under either zero point.
 */
object AccountChartMapping {

    /** The chart data for [view]; anything that can't be drawn (no forecast for that account) falls back to the total. */
    fun build(
        view: AccountView,
        result: ForecastResult,
        accounts: List<Account>,
        zeroPoint: ZeroPoint,
        buffer: Money,
    ): ForecastChartData {
        val totalDays = ForecastChartMapping.toChartDays(result, zeroPoint)
        val total = ForecastChartData(AccountView.Total, totalDays, lines = null, buffer = buffer, hasTier1 = true)
        return when (view) {
            AccountView.Total -> total
            is AccountView.Single -> {
                val forecast = result.accountForecasts.firstOrNull { it.accountId == view.accountId } ?: return total
                ForecastChartData(view, accountDays(result, forecast, zeroPoint), lines = null, buffer = Money.ZERO, hasTier1 = false)
            }
            AccountView.Each -> {
                val lines = eachLines(result, accounts, zeroPoint)
                if (lines.isEmpty()) {
                    total
                } else {
                    ForecastChartData(view, totalDays, lines = lines, buffer = Money.ZERO, hasTier1 = false)
                }
            }
        }
    }

    /** One account's P10–P90 band and expected line, aligned with [ForecastResult.days] (no tier-1 line: that is pool-only). */
    fun accountDays(result: ForecastResult, forecast: AccountForecast, zeroPoint: ZeroPoint): List<ForecastChartDay> {
        val regimeByDate: Map<LocalDate, Regime> = result.days.associate { it.date to it.regime }
        return forecast.days.map { d ->
            ForecastChartDay(
                date = d.date,
                regime = regimeByDate[d.date] ?: Regime.HOLIDAY,
                p10 = d.p10?.let { Balances.toDisplay(it, forecast.overdraft, zeroPoint) },
                p50 = Balances.toDisplay(d.p50 ?: d.expectedBalance, forecast.overdraft, zeroPoint),
                p90 = d.p90?.let { Balances.toDisplay(it, forecast.overdraft, zeroPoint) },
                tier1 = null,
            )
        }
    }

    /**
     * One expected-balance line per non-archived account that has a forecast, in creation (id)
     * order so an account keeps its colour when others are added.
     */
    fun eachLines(result: ForecastResult, accounts: List<Account>, zeroPoint: ZeroPoint): List<ForecastChartLine> {
        val namesById = accounts.associate { it.id to it.name }
        return result.accountForecasts.sortedBy { it.accountId }.map { forecast ->
            ForecastChartLine(
                label = namesById[forecast.accountId] ?: "Account ${forecast.accountId}",
                points = forecast.days.map { d ->
                    d.date to Balances.toDisplay(d.expectedBalance, forecast.overdraft, zeroPoint)
                },
            )
        }
    }

    /** [days] cut off after [horizonEnd] (the Forecast screen's 4/12/26-week and "until next income" chips). */
    fun cutAt(data: ForecastChartData, horizonEnd: LocalDate): ForecastChartData = data.copy(
        days = data.days.filter { !it.date.isAfter(horizonEnd) },
        lines = data.lines?.map { line -> line.copy(points = line.points.filter { !it.first.isAfter(horizonEnd) }) },
    )
}
