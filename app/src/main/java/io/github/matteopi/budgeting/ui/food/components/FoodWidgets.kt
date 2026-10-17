package io.github.matteopi.budgeting.ui.food.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.recipes.Light
import io.github.matteopi.budgeting.ui.theme.LocalIsDarkTheme
import io.github.matteopi.budgeting.ui.theme.LocalMoneyColors
import kotlin.math.ceil

/*
 * Small pieces the Recipes screens share (docs/PLAN.md §11.8): traffic-light dots with their words, the 5 a day
 * dots, star ratings, the servings stepper and a pill for a number.
 */

private val AMBER_ON_LIGHT = Color(0xFFB26A00)
private val AMBER_ON_DARK = Color(0xFFFFB74D)

/**
 * The colour of a traffic light: green from the theme's "good money" colour, red from the error colour and amber
 * a fixed amber that suits the light or dark theme. Always shown with its word ([LightBadge]) so colour is never
 * the only signal.
 */
@Composable
fun lightColor(light: Light): Color = when (light) {
    Light.GREEN -> LocalMoneyColors.current.income
    Light.AMBER -> if (LocalIsDarkTheme.current) AMBER_ON_DARK else AMBER_ON_LIGHT
    Light.RED -> MaterialTheme.colorScheme.error
}

/** A round dot in the colour of [light]. */
@Composable
fun LightDot(light: Light, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(lightColor(light)))
}

/** The dot and its word: "High", "Med" or "Low". */
@Composable
fun LightBadge(light: Light, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LightDot(light)
        Spacer(Modifier.width(4.dp))
        Text(FoodFormat.lightLabel(light), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 5 a day as dots, full and part: at least five, more when [veg] is over five. Followed by the number by the caller.
 */
@Composable
fun VegDots(veg: Double, modifier: Modifier = Modifier, dotSize: Dp = 10.dp) {
    val count = maxOf(5, ceil(veg - 1e-9).toInt())
    val gap = 3.dp
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .size(width = (dotSize + gap) * count - gap, height = dotSize)
            .semantics { contentDescription = "${FoodFormat.veg(veg)} portions of veg" },
    ) {
        val diameter = dotSize.toPx()
        val step = diameter + gap.toPx()
        for (i in 0 until count) {
            val left = i * step
            drawCircle(color = empty, radius = diameter / 2, center = Offset(left + diameter / 2, diameter / 2))
            val fraction = (veg - i).coerceIn(0.0, 1.0).toFloat()
            if (fraction > 0f) {
                drawArc(
                    color = filled,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = true,
                    topLeft = Offset(left, 0f),
                    size = Size(diameter, diameter),
                )
            }
        }
    }
}

/** A rating as five small stars, or nothing when [rating] is 0 (not rated). */
@Composable
fun RatingStars(rating: Int, modifier: Modifier = Modifier, starSize: Dp = 16.dp) {
    if (rating <= 0) return
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = FoodFormat.ratingText(rating) }) {
        for (i in 1..5) {
            Icon(
                imageVector = if (i <= rating) FoodIcons.Star else FoodIcons.StarOutline,
                contentDescription = null,
                modifier = Modifier.size(starSize),
                tint = if (i <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Five stars to tap, with "Rated 4 out of 5" under them. Tapping the star that is already the rating clears it.
 * The stars are described "Rate 4 stars".
 */
@Composable
fun RatingPicker(rating: Int, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row {
            for (n in 1..5) {
                IconButton(onClick = { onRate(if (n == rating) 0 else n) }) {
                    Icon(
                        imageVector = if (n <= rating) FoodIcons.Star else FoodIcons.StarOutline,
                        contentDescription = if (n == 1) "Rate 1 star" else "Rate $n stars",
                        tint = if (n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        Text(
            text = FoodFormat.ratingText(rating),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A minus button, the number, and a plus button. The buttons are described "Fewer [noun]" and "More [noun]"
 * (so two steppers on a screen can have different nouns), and are disabled at the ends of [range].
 */
@Composable
fun ServingsStepper(
    servings: Int,
    onServingsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    noun: String = "servings",
    range: IntRange = 1..24,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onServingsChange(servings - 1) }, enabled = servings > range.first) {
            Icon(FoodIcons.Remove, contentDescription = "Fewer $noun")
        }
        Text(
            text = servings.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 32.dp)
                .semantics { contentDescription = "$servings $noun" },
        )
        IconButton(onClick = { onServingsChange(servings + 1) }, enabled = servings < range.last) {
            Icon(Icons.Default.Add, contentDescription = "More $noun")
        }
    }
}

/** A rounded pill holding one number of a recipe (its cost, kcal, salt…). */
@Composable
fun NumberChip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
