package io.github.matteopi.budgeting.ui.components.charts

import androidx.compose.ui.graphics.Color
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import java.time.LocalDate

/**
 * Plain data types every chart composable takes, so charts stay pure functions of data with no
 * ViewModel/domain-service dependency. Screens (or their ViewModels) do the mapping from core
 * models — e.g. converting raw balances to the user's display terms — before calling a chart.
 */

/** One day of [ForecastChart] data, already converted to display terms (see `Balances.toDisplay`). */
data class ForecastChartDay(
    val date: LocalDate,
    val regime: Regime,
    val p10: Money?,
    val p50: Money,
    val p90: Money?,
    val tier1: Money?,
)

/**
 * One account's expected-balance line on the forecast chart's "Each account" view, in display
 * terms. Colours are assigned by the chart, in the order of the list, from
 * [ChartColors.accountSeries].
 */
data class ForecastChartLine(val label: String, val points: List<Pair<LocalDate, Money>>)

/** One segment of a stacked bar, e.g. one category's spend in a week. [amount] is a positive magnitude. */
data class StackedBarSegment(val label: String, val color: Color, val amount: Money)

/** One stacked bar, e.g. one week's spending split by category. */
data class StackedBarGroup(val label: String, val segments: List<StackedBarSegment>)

/** One bar. [value] may be negative (drawn below the zero baseline). */
data class BarChartEntry(val label: String, val value: Money, val color: Color)

/** One group of bars sharing an x position, e.g. a month's "in" and "out" bars. */
data class BarChartGroup(val label: String, val bars: List<BarChartEntry>)

/** One line series over time, e.g. balance history. */
data class LineChartSeries(val label: String, val color: Color, val points: List<Pair<LocalDate, Money>>)
