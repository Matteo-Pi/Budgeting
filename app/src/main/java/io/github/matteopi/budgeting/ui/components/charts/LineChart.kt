package io.github.matteopi.budgeting.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate

/**
 * One or more lines over time, e.g. balance history. [referenceLine] (default £0) draws a dashed
 * horizontal reference. With two or more series a legend (colour + name) is shown under the chart,
 * so a line is never identified by colour alone. See docs/PLAN.md §6.11.
 */
@Composable
fun LineChart(
    series: List<LineChartSeries>,
    modifier: Modifier = Modifier,
    referenceLine: Money? = Money.ZERO,
) {
    val points = series.flatMap { it.points }
    if (points.isEmpty()) return
    val colors = rememberChartColors()
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = modifier.fillMaxWidth()) {
        LineChartCanvas(series, points, referenceLine, colors, textMeasurer)
        if (series.size > 1) {
            Row(modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
                series.forEach { s -> LegendItem(s.label, s.color) }
            }
        }
    }
}

@Composable
private fun LineChartCanvas(
    series: List<LineChartSeries>,
    points: List<Pair<LocalDate, Money>>,
    referenceLine: Money?,
    colors: ChartColors,
    textMeasurer: TextMeasurer,
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        val plotLeft = 44.dp.toPx()
        val plotTop = 8.dp.toPx()
        val plotBottom = size.height - 8.dp.toPx()
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        val startDate = points.minOf { it.first }
        val endDate = points.maxOf { it.first }
        val totalDays = (endDate.toEpochDay() - startDate.toEpochDay()).toFloat().coerceAtLeast(1f)

        val values = points.map { it.second.pence } + listOfNotNull(referenceLine?.pence)
        val dataMax = values.max()
        val dataMin = values.min()
        val rawRange = dataMax - dataMin
        val pad = if (rawRange > 0L) rawRange / 10 else 1000L
        val yMin = dataMin - pad
        val yMax = dataMax + pad
        val yRange = (yMax - yMin).coerceAtLeast(1L)

        fun xFor(date: LocalDate): Float {
            val d = (date.toEpochDay() - startDate.toEpochDay()).toFloat()
            return plotLeft + (d / totalDays) * (size.width - plotLeft)
        }
        fun yFor(pence: Long): Float {
            val t = (pence - yMin).toFloat() / yRange
            return plotBottom - t * plotHeight
        }

        val labelStyle = TextStyle(fontSize = 10.sp, color = colors.mutedInk)
        drawText(textMeasurer, Money(dataMax).format(showPence = false), Offset(0f, plotTop), labelStyle)
        drawText(textMeasurer, Money(dataMin).format(showPence = false), Offset(0f, plotBottom - 12.dp.toPx()), labelStyle)

        referenceLine?.let { ref ->
            val y = yFor(ref.pence)
            drawLine(
                color = colors.baseline,
                start = Offset(plotLeft, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }

        series.forEach { s ->
            if (s.points.isEmpty()) return@forEach
            val path = Path()
            val sorted = s.points.sortedBy { it.first }
            sorted.forEachIndexed { i, point ->
                val x = xFor(point.first)
                val y = yFor(point.second.pence)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
