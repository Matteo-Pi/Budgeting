package io.github.matteopi.budgeting.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matteopi.budgeting.core.model.Money

/**
 * One bar per [StackedBarGroup] (e.g. one week), each split into coloured segments (e.g. one per
 * category) with a small gap between segments. Used by the Insights screen's "weekly spending by
 * category" chart. See docs/PLAN.md §6.11.
 */
@Composable
fun WeeklyStackedBarChart(
    groups: List<StackedBarGroup>,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
) {
    StackedBarChart(
        groups = groups.map { g -> StackedValueGroup(g.label, g.segments.map { StackedValueSegment(it.label, it.color, it.amount.pence) }) },
        axisLabel = { Money(it).format(showPence = false) },
        minimumTop = 100L,
        modifier = modifier,
        onSelect = onSelect,
    )
}

/**
 * Stacked bars of any positive amount (pence, minutes): one bar per [StackedValueGroup], segments bottom up with a
 * 2 dp gap between them, the top value on the axis as [axisLabel] gives it, and the first and last labels under the
 * bars. [selected] draws the other bars faded; a tap reports the bar's index.
 */
@Composable
fun StackedBarChart(
    groups: List<StackedValueGroup>,
    axisLabel: (Long) -> String,
    modifier: Modifier = Modifier,
    minimumTop: Long = 1L,
    selected: Int? = null,
    onSelect: (Int) -> Unit = {},
) {
    if (groups.isEmpty()) return
    val colors = rememberChartColors()
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(groups) {
                detectTapGestures { offset ->
                    val index = ((offset.x / size.width) * groups.size).toInt().coerceIn(0, groups.size - 1)
                    onSelect(index)
                }
            },
    ) {
        val plotLeft = 40.dp.toPx()
        val plotTop = 8.dp.toPx()
        val plotBottom = size.height - 20.dp.toPx()
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
        val maxTotal = groups.maxOf { g -> g.segments.sumOf { it.value.coerceAtLeast(0) } }.coerceAtLeast(minimumTop)

        val slotWidth = (size.width - plotLeft) / groups.size
        val barWidth = (slotWidth * 0.6f).coerceAtLeast(4f)
        val gap = 2.dp.toPx()

        val labelStyle = TextStyle(fontSize = 10.sp, color = colors.mutedInk)
        drawLine(colors.baseline, Offset(plotLeft, plotBottom), Offset(size.width, plotBottom), strokeWidth = 1.dp.toPx())
        drawText(
            textMeasurer = textMeasurer,
            text = axisLabel(maxTotal),
            topLeft = Offset(0f, plotTop),
            style = labelStyle,
        )

        groups.forEachIndexed { index, group ->
            val slotCenter = plotLeft + slotWidth * (index + 0.5f)
            val faded = selected != null && selected != index
            var yCursor = plotBottom
            for (segment in group.segments) {
                if (segment.value <= 0) continue
                val segmentHeight = (segment.value.toFloat() / maxTotal) * plotHeight
                val top = (yCursor - segmentHeight).coerceAtLeast(plotTop)
                val drawnHeight = (yCursor - top - gap).coerceAtLeast(0f)
                if (drawnHeight > 0f) {
                    drawRect(
                        color = if (faded) segment.color.copy(alpha = 0.35f) else segment.color,
                        topLeft = Offset(slotCenter - barWidth / 2, top),
                        size = Size(barWidth, drawnHeight),
                    )
                }
                yCursor = top - gap
            }
        }

        // Label selectively: only the first and last bar, to avoid crowding at phone width.
        val first = groups.first()
        drawText(textMeasurer, first.label, Offset(plotLeft, plotBottom + 4.dp.toPx()), labelStyle)
        if (groups.size > 1) {
            val last = groups.last()
            val lastWidth = textMeasurer.measure(last.label, labelStyle).size.width
            val lastX = (plotLeft + slotWidth * (groups.size - 0.5f) - lastWidth / 2f)
                .coerceIn(plotLeft, (size.width - lastWidth).coerceAtLeast(plotLeft))
            drawText(textMeasurer, last.label, Offset(lastX, plotBottom + 4.dp.toPx()), labelStyle)
        }
    }
}
