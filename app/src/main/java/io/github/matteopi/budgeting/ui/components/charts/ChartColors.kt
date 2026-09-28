package io.github.matteopi.budgeting.ui.components.charts

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour roles shared by every chart under `ui/components/charts`. Chosen and checked with the
 * `dataviz` skill: one colour per job (a fixed categorical order for identity, a single hue for
 * the forecast band's magnitude, reserved status colours for the floor/buffer thresholds), text
 * never carrying a data colour, and light/dark pairs that both keep readable contrast on their
 * own surface. [seriesPrimary]/[seriesSecondary] are the skill's categorical slots 1 (blue) and 2
 * (orange); [good]/[critical] reuse this app's existing income-green/negative-red convention
 * (see [io.github.matteopi.budgeting.ui.components.MoneyText]) so money-in/money-out charts read
 * the same way as the rest of the app.
 */
@Immutable
data class ChartColors(
    /** Axis-tick and reference-line text drawn inside a Canvas. */
    val mutedInk: Color,
    /** A reference line that isn't a data series, e.g. the tier-1 dashed baseline. */
    val secondaryInk: Color,
    val gridline: Color,
    val baseline: Color,
    val seriesPrimary: Color,
    val seriesPrimaryFill: Color,
    val seriesSecondary: Color,
    val good: Color,
    val critical: Color,
    val warning: Color,
)

private val LightChartColors = ChartColors(
    mutedInk = Color(0xFF898781),
    secondaryInk = Color(0xFF52514E),
    gridline = Color(0xFFE1E0D9),
    baseline = Color(0xFFC3C2B7),
    seriesPrimary = Color(0xFF2A78D6),
    seriesPrimaryFill = Color(0x262A78D6),
    seriesSecondary = Color(0xFFEB6834),
    good = Color(0xFF2E7D32),
    critical = Color(0xFFC62828),
    warning = Color(0xFFC98500),
)

private val DarkChartColors = ChartColors(
    mutedInk = Color(0xFF898781),
    secondaryInk = Color(0xFFC3C2B7),
    gridline = Color(0xFF2C2C2A),
    baseline = Color(0xFF383835),
    seriesPrimary = Color(0xFF3987E5),
    seriesPrimaryFill = Color(0x333987E5),
    seriesSecondary = Color(0xFFD95926),
    good = Color(0xFF66BB6A),
    critical = Color(0xFFEF5350),
    warning = Color(0xFFEDA100),
)

/** Picks [LightChartColors] or [DarkChartColors] to match the system theme (docs/PLAN.md §6.11). */
@Composable
fun rememberChartColors(): ChartColors = if (isSystemInDarkTheme()) DarkChartColors else LightChartColors
