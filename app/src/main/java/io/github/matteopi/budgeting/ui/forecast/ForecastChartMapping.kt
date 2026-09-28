package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.RiskSummary
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.ui.components.charts.ForecastChartDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How much of [ForecastResult.days] the Forecast chart shows; the engine always computes the full horizon. */
enum class ForecastHorizonOption(val label: String) {
    WEEKS_4("4 wks"),
    WEEKS_12("12 wks"),
    UNTIL_NEXT_INCOME("Until next income"),
    WEEKS_26("26 wks"),
}

/**
 * Pure mapping from the engine's raw-balance [ForecastResult] to the chart's display-terms plain
 * data, plus the risk sentence's wording. No Android/Compose imports, so it is unit-testable on
 * the plain JVM. See docs/PLAN.md §5 (raw vs display balances) and §6.5 (Forecast screen).
 */
object ForecastChartMapping {

    /** [ForecastResult.days], each converted to the user's display terms. */
    fun toChartDays(result: ForecastResult, zeroPoint: ZeroPoint): List<ForecastChartDay> =
        result.days.map { d ->
            ForecastChartDay(
                date = d.date,
                regime = d.regime,
                p10 = d.p10?.let { Balances.toDisplay(it, result.poolOverdraft, zeroPoint) },
                p50 = Balances.toDisplay(d.p50 ?: d.expectedBalance, result.poolOverdraft, zeroPoint),
                p90 = d.p90?.let { Balances.toDisplay(it, result.poolOverdraft, zeroPoint) },
                tier1 = Balances.toDisplay(d.tier1Balance, result.poolOverdraft, zeroPoint),
            )
        }

    /** The last date the chart shows for [option], capped at the engine's own forecast end. */
    fun horizonEndDate(option: ForecastHorizonOption, result: ForecastResult): LocalDate {
        val cap = result.forecastEnd
        return when (option) {
            ForecastHorizonOption.WEEKS_4 -> minOf(result.today.plusWeeks(4), cap)
            ForecastHorizonOption.WEEKS_12 -> minOf(result.today.plusWeeks(12), cap)
            ForecastHorizonOption.UNTIL_NEXT_INCOME -> minOf(result.safeToSpend.horizonEnd, cap)
            ForecastHorizonOption.WEEKS_26 -> minOf(result.today.plusWeeks(26), cap)
        }
    }

    /** "x% chance of going below £0 before <date>", or the reassuring line under 5%. */
    fun riskSentence(risk: RiskSummary?, horizonEnd: LocalDate): String {
        if (risk == null) return "Working out the risk of going below £0…"
        return if (risk.probBelowFloor < 0.05) {
            "Very unlikely to go below £0."
        } else {
            val percent = Math.round(risk.probBelowFloor * 100.0)
            "$percent% chance of going below £0 before ${horizonEnd.toShortLabel()}."
        }
    }
}

private val SHORT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

/** "5 Oct" — the short date label shared by the Forecast, Timeline and What-if screens. */
fun LocalDate.toShortLabel(): String = format(SHORT_DATE_FORMATTER)
