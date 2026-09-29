package io.github.matteopi.budgeting.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matteopi.budgeting.core.model.Money
import kotlin.math.abs

/**
 * Grouped bars around a zero baseline — e.g. income vs spending per month, term vs holiday
 * averages, or (with a single bar per group) one category's weekly amounts. Bar values may be
 * negative (spending), drawn below the baseline. An optional [referenceLine] draws a dashed
 * horizontal line, e.g. a median. See docs/PLAN.md §6.11.
 */
@Composable
fun BarChart(
    groups: List<BarChartGroup>,
    modifier: Modifier = Modifier,
    referenceLine: Money? = null,
    referenceLabel: String? = null,
) {
    if (groups.isEmpty()) return
    val colors = rememberChartColors()
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier.fillMaxWidth().height(180.dp)) {
        val plotLeft = 44.dp.toPx()
        val plotTop = 10.dp.toPx()
        val plotBottom = size.height - 20.dp.toPx()
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        val values = groups.flatMap { it.bars.map { bar -> bar.value.pence } } +
            listOfNotNull(referenceLine?.pence) + listOf(0L)
        val maxV = values.max().coerceAtLeast(0L)
        val minV = values.min().coerceAtMost(0L)
        val range = (maxV - minV).coerceAtLeast(100L)

        fun yFor(pence: Long): Float {
            val t = (pence - minV).toFloat() / range
            return plotBottom - t * plotHeight
        }
        val zeroY = yFor(0L)

        val labelStyle = TextStyle(fontSize = 10.sp, color = colors.mutedInk)
        drawLine(colors.baseline, Offset(plotLeft, zeroY), Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())
        drawText(textMeasurer, Money(maxV).format(showPence = false), Offset(0f, plotTop), labelStyle)
        drawText(textMeasurer, Money(minV).format(showPence = false), Offset(0f, plotBottom - 12.dp.toPx()), labelStyle)

        val groupWidth = (size.width - plotLeft) / groups.size
        val gap = 2.dp.toPx()

        // Labels that would overlap the previous one are left out (12 weekly dates don't all fit).
        var labelsEndX = Float.NEGATIVE_INFINITY
        groups.forEachIndexed { groupIndex, group ->
            val barsCount = group.bars.size.coerceAtLeast(1)
            val barWidth = ((groupWidth * 0.7f - gap * (barsCount - 1)) / barsCount).coerceAtLeast(4f)
            val groupStart = plotLeft + groupWidth * groupIndex + groupWidth * 0.15f
            group.bars.forEachIndexed { barIndex, bar ->
                val x = groupStart + barIndex * (barWidth + gap)
                val y = yFor(bar.value.pence)
                val top = minOf(y, zeroY)
                val barHeight = abs(y - zeroY)
                drawRect(color = bar.color, topLeft = Offset(x, top), size = Size(barWidth, barHeight))
            }
            val label = textMeasurer.measure(group.label, labelStyle)
            val labelWidth = label.size.width
            val labelX = (plotLeft + groupWidth * (groupIndex + 0.5f) - labelWidth / 2f)
                .coerceIn(0f, (size.width - labelWidth).coerceAtLeast(0f))
            if (labelX >= labelsEndX) {
                drawText(textLayoutResult = label, topLeft = Offset(labelX, plotBottom + 4.dp.toPx()))
                labelsEndX = labelX + labelWidth + 6.dp.toPx()
            }
        }

        referenceLine?.let { ref ->
            val y = yFor(ref.pence)
            drawLine(
                color = colors.seriesSecondary,
                start = Offset(plotLeft, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
            referenceLabel?.let { label ->
                drawText(textMeasurer, label, Offset(plotLeft + 4.dp.toPx(), y - 12.dp.toPx()), labelStyle)
            }
        }
    }
}
