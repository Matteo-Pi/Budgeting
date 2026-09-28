package io.github.matteopi.budgeting.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MMM", Locale.UK)
private val DAY_MONTH_FORMATTER = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

/**
 * The Forecast screen's headline chart: shaded P10-P90 uncertainty band, the P50/expected line,
 * an optional dashed tier-1 baseline, a floor line at display £0, a buffer line, light shading
 * over TERM days, a "today" marker, an optional scenario overlay, and tap-to-select. Every amount
 * in [days] must already be converted to the user's display terms (see
 * [io.github.matteopi.budgeting.core.forecast.Balances.toDisplay]) so this composable stays a
 * pure function of plain data. See docs/PLAN.md §6.11.
 */
@Composable
fun ForecastChart(
    days: List<ForecastChartDay>,
    buffer: Money,
    today: LocalDate,
    modifier: Modifier = Modifier,
    showTier1: Boolean = false,
    scenario: List<ForecastChartDay>? = null,
    onSelectDay: (LocalDate) -> Unit = {},
) {
    if (days.isEmpty()) return
    val colors = rememberChartColors()
    val textMeasurer = rememberTextMeasurer()
    var selected by remember(days) { mutableStateOf<ForecastChartDay?>(null) }

    Column(modifier = modifier) {
        val infoDay = selected ?: days.firstOrNull { it.date == today } ?: days.first()
        SelectionLabel(infoDay)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(days) {
                    detectTapGestures { offset ->
                        val chosen = nearestDay(days, offset.x, size.width.toFloat())
                        selected = chosen
                        onSelectDay(chosen.date)
                    }
                },
        ) {
            val plotLeft = 44.dp.toPx()
            val plotRight = size.width
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotTop = 8.dp.toPx()
            val plotBottom = size.height - 20.dp.toPx()
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

            val startDate = days.first().date
            val endDate = days.last().date
            val totalDays = (endDate.toEpochDay() - startDate.toEpochDay()).toFloat().coerceAtLeast(1f)

            fun xFor(date: LocalDate): Float {
                val offsetDays = (date.toEpochDay() - startDate.toEpochDay()).toFloat()
                return plotLeft + (offsetDays / totalDays) * plotWidth
            }

            val allValues = mutableListOf(0.0, buffer.pence.toDouble())
            for (d in days) {
                allValues += d.p50.pence.toDouble()
                d.p10?.let { allValues += it.pence.toDouble() }
                d.p90?.let { allValues += it.pence.toDouble() }
                if (showTier1) d.tier1?.let { allValues += it.pence.toDouble() }
            }
            scenario?.forEach { allValues += it.p50.pence.toDouble() }
            val dataMin = allValues.min()
            val dataMax = allValues.max()
            val rawRange = dataMax - dataMin
            val padding = if (rawRange > 0.0) rawRange * 0.08 else 1000.0
            val yMin = dataMin - padding
            val yMax = dataMax + padding
            val yRange = (yMax - yMin).coerceAtLeast(1.0)

            fun yFor(pence: Long): Float {
                val t = ((pence - yMin) / yRange).toFloat()
                return plotBottom - t * plotHeight
            }

            // Light shading over contiguous runs of TERM days.
            var runStart: LocalDate? = null
            for (i in days.indices) {
                val isTerm = days[i].regime == Regime.TERM
                if (isTerm && runStart == null) runStart = days[i].date
                val isLast = i == days.lastIndex
                if ((!isTerm || isLast) && runStart != null) {
                    val runEndDate = if (isTerm && isLast) days[i].date else days[i - 1].date
                    val left = xFor(runStart)
                    val right = xFor(runEndDate).coerceAtLeast(left + 1f)
                    drawRect(
                        color = colors.mutedInk.copy(alpha = 0.07f),
                        topLeft = Offset(left, plotTop),
                        size = Size(right - left, plotHeight),
                    )
                    runStart = null
                }
            }

            val labelStyle = TextStyle(fontSize = 10.sp, color = colors.mutedInk)

            // Y-axis: max, floor (£0) and min labels + gridlines.
            listOf(dataMax, 0.0, dataMin).distinct().forEach { value ->
                val y = yFor(value.toLong())
                drawLine(colors.gridline, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
                drawText(
                    textMeasurer = textMeasurer,
                    text = Money(value.toLong()).format(showPence = false),
                    topLeft = Offset(0f, (y - 6.dp.toPx()).coerceAtLeast(0f)),
                    style = labelStyle,
                )
            }

            // X-axis: one gridline + short month label per calendar month.
            ChartAxis.monthTicks(startDate, endDate).forEach { date ->
                val x = xFor(date)
                drawLine(colors.gridline, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
                drawText(
                    textMeasurer = textMeasurer,
                    text = date.format(MONTH_LABEL_FORMATTER),
                    topLeft = Offset(x.coerceAtLeast(plotLeft), plotBottom + 4.dp.toPx()),
                    style = labelStyle,
                )
            }

            // P10-P90 uncertainty band.
            if (days.any { it.p10 != null && it.p90 != null }) {
                val band = Path()
                var started = false
                for (d in days) {
                    val p90 = d.p90 ?: continue
                    val x = xFor(d.date)
                    val y = yFor(p90.pence)
                    if (!started) {
                        band.moveTo(x, y)
                        started = true
                    } else {
                        band.lineTo(x, y)
                    }
                }
                for (d in days.asReversed()) {
                    val p10 = d.p10 ?: continue
                    band.lineTo(xFor(d.date), yFor(p10.pence))
                }
                band.close()
                drawPath(band, color = colors.seriesPrimaryFill)
            }

            // Floor line at display £0.
            val floorY = yFor(0L)
            drawLine(
                color = colors.critical,
                start = Offset(plotLeft, floorY),
                end = Offset(plotRight, floorY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )

            // Buffer line.
            if (buffer.pence > 0) {
                val bufferY = yFor(buffer.pence)
                drawLine(
                    color = colors.warning,
                    start = Offset(plotLeft, bufferY),
                    end = Offset(plotRight, bufferY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
            }

            // Tier-1 dashed baseline.
            if (showTier1) {
                val tier1Path = Path()
                var startedTier1 = false
                for (d in days) {
                    val v = d.tier1 ?: continue
                    val x = xFor(d.date)
                    val y = yFor(v.pence)
                    if (!startedTier1) {
                        tier1Path.moveTo(x, y)
                        startedTier1 = true
                    } else {
                        tier1Path.lineTo(x, y)
                    }
                }
                drawPath(
                    tier1Path,
                    color = colors.secondaryInk,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
                    ),
                )
            }

            // P50 / expected line.
            val p50Path = Path()
            days.forEachIndexed { i, d ->
                val x = xFor(d.date)
                val y = yFor(d.p50.pence)
                if (i == 0) p50Path.moveTo(x, y) else p50Path.lineTo(x, y)
            }
            drawPath(p50Path, color = colors.seriesPrimary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

            // Scenario overlay (P50 only).
            if (scenario != null && scenario.isNotEmpty()) {
                val scenarioPath = Path()
                scenario.forEachIndexed { i, d ->
                    val x = xFor(d.date)
                    val y = yFor(d.p50.pence)
                    if (i == 0) scenarioPath.moveTo(x, y) else scenarioPath.lineTo(x, y)
                }
                drawPath(scenarioPath, color = colors.seriesSecondary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }

            // Today marker.
            if (!today.isBefore(startDate) && !today.isAfter(endDate)) {
                val x = xFor(today)
                drawLine(colors.mutedInk, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
            }
        }

        if (showTier1 || scenario != null) {
            Row(modifier = Modifier.padding(top = 4.dp)) {
                LegendItem("Expected", colors.seriesPrimary)
                if (showTier1) LegendItem("Tier-1 baseline", colors.secondaryInk)
                if (scenario != null) LegendItem("Scenario", colors.seriesSecondary)
            }
        }
    }
}

private fun nearestDay(days: List<ForecastChartDay>, x: Float, width: Float): ForecastChartDay {
    if (days.size == 1) return days.first()
    val start = days.first().date.toEpochDay()
    val end = days.last().date.toEpochDay()
    val total = (end - start).coerceAtLeast(1)
    val fraction = (x / width).coerceIn(0f, 1f)
    val targetDay = start + Math.round(fraction * total)
    return days.minByOrNull { Math.abs(it.date.toEpochDay() - targetDay) } ?: days.first()
}

@Composable
private fun SelectionLabel(day: ForecastChartDay) {
    val range = if (day.p10 != null && day.p90 != null) {
        " (${day.p10.format(showPence = false)} to ${day.p90.format(showPence = false)})"
    } else {
        ""
    }
    Text(
        text = "${day.date.format(DAY_MONTH_FORMATTER)}: ${day.p50.format(showPence = false)}$range",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = color) }
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
