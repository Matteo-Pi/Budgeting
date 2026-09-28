package io.github.matteopi.budgeting.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Money

/**
 * A small line-and-area sparkline of the expected pool balance for the weeks ahead, drawn with
 * Compose Canvas (docs/PLAN.md §6.5). Deliberately minimal — no axes or tooltip — the full
 * interactive chart lives on Forecast (WP-U4); tapping this opens it.
 */
@Composable
fun HomeSparkline(
    points: List<Money>,
    floor: Money,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val floorColor = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClickLabel = "Open Forecast", onClick = onClick)
            .semantics { contentDescription = "Expected balance over the next 8 weeks" },
    ) {
        if (points.size < 2) return@Canvas

        val values = points.map { it.pence.toFloat() }
        val minValue = minOf(values.min(), floor.pence.toFloat())
        val maxValue = maxOf(values.max(), floor.pence.toFloat())
        val range = (maxValue - minValue).coerceAtLeast(1f)

        fun yFor(value: Float): Float = size.height - ((value - minValue) / range) * size.height
        val stepX = size.width / (values.size - 1)

        val floorY = yFor(floor.pence.toFloat())
        drawLine(
            color = floorColor,
            start = Offset(0f, floorY),
            end = Offset(size.width, floorY),
            strokeWidth = 1.dp.toPx(),
        )

        val linePath = Path()
        val fillPath = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = yFor(value)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        drawPath(path = fillPath, color = fillColor)
        drawPath(path = linePath, color = lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}
