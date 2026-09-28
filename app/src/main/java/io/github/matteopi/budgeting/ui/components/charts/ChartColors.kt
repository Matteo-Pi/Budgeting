package io.github.matteopi.budgeting.ui.components.charts

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.matteopi.budgeting.ui.theme.LocalIsDarkTheme
import io.github.matteopi.budgeting.ui.theme.LocalMoneyColors
import io.github.matteopi.budgeting.ui.theme.MoneyColors
import io.github.matteopi.budgeting.ui.theme.ThemePalettes

/**
 * Colour roles shared by every chart under `ui/components/charts`, derived from the active
 * Material colour scheme so charts follow the chosen theme (docs/PLAN.md §6.11): the main series
 * and forecast band come from `primary`, axis text from `onSurfaceVariant`, gridlines from
 * `outlineVariant`, the baseline and today marker from `outline`. The scenario overlay uses
 * `tertiary`, which every theme picks to contrast with its `primary`. The floor line and
 * money-out bars use the theme's negative money colour ([critical]) and money-in bars its income
 * colour ([good]), as in [io.github.matteopi.budgeting.ui.components.MoneyText]; the buffer line
 * stays amber ([warning]), so status colours read the same in every theme. Text never carries a
 * data colour.
 */
@Immutable
data class ChartColors(
    /** Axis-tick and reference-line text drawn inside a Canvas. */
    val mutedInk: Color,
    /** A reference line that isn't a data series, e.g. the tier-1 dashed baseline. */
    val secondaryInk: Color,
    val gridline: Color,
    val baseline: Color,
    /** The vertical "today" line on the forecast chart. */
    val todayMarker: Color,
    val seriesPrimary: Color,
    val seriesPrimaryFill: Color,
    val seriesSecondary: Color,
    val good: Color,
    val critical: Color,
    val warning: Color,
)

/** Derives the chart colours from a Material [scheme], the theme's [money] colours and light/dark mode. */
internal fun chartColorsFor(scheme: ColorScheme, money: MoneyColors, dark: Boolean): ChartColors = ChartColors(
    mutedInk = scheme.onSurfaceVariant,
    secondaryInk = scheme.onSurfaceVariant,
    gridline = scheme.outlineVariant,
    baseline = scheme.outline,
    todayMarker = scheme.outline,
    seriesPrimary = scheme.primary,
    seriesPrimaryFill = scheme.primary.copy(alpha = if (dark) 0.22f else 0.14f),
    seriesSecondary = scheme.tertiary,
    good = money.income,
    critical = money.negative,
    warning = Color(if (dark) ThemePalettes.DARK_WARNING else ThemePalettes.LIGHT_WARNING),
)

/**
 * The chart colours for the active [io.github.matteopi.budgeting.ui.theme.BudgetingTheme]. Not
 * remembered: Material mutates its colour-scheme instance in place, so keying on it would go stale.
 */
@Composable
fun rememberChartColors(): ChartColors =
    chartColorsFor(MaterialTheme.colorScheme, LocalMoneyColors.current, LocalIsDarkTheme.current)
