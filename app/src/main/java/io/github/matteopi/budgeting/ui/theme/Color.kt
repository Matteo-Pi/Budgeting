package io.github.matteopi.budgeting.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.matteopi.budgeting.data.settings.AppTheme

/**
 * The colours for money: [income] for money coming in (and positive transaction amounts) and
 * [negative] for negative balances or anything over its limit. They are muted and tuned per theme
 * so they stay legible on `background`, `surface` and card tones (docs/PLAN.md §6.12).
 */
@Immutable
data class MoneyColors(
    val income: Color,
    val negative: Color,
)

/** Money colours for the active theme. The default is Paper's light pair, used outside [BudgetingTheme]. */
val LocalMoneyColors = staticCompositionLocalOf { ThemePalettes.palette(AppTheme.PAPER, dark = false).toMoneyColors() }

/** True when the active [BudgetingTheme] resolved to its dark palette (which may differ from the system setting). */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

internal fun PaletteSpec.toMoneyColors(): MoneyColors = MoneyColors(
    income = Color(income),
    negative = Color(negative),
)

/** The full Material 3 light scheme for this palette, including every surface-container role. */
internal fun PaletteSpec.toLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(primary),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    surfaceDim = Color(surfaceDim),
    surfaceBright = Color(surfaceBright),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
)

/** The full Material 3 dark scheme for this palette, including every surface-container role. */
internal fun PaletteSpec.toDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(primary),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    surfaceDim = Color(surfaceDim),
    surfaceBright = Color(surfaceBright),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
)

/**
 * The Material 3 scheme for [theme] in the requested mode. [AppTheme.DYNAMIC] resolves to Paper
 * here; wallpaper colours are applied by [rememberColorScheme], which needs a `Context`.
 */
fun colorSchemeFor(theme: AppTheme, dark: Boolean): ColorScheme {
    val palette = ThemePalettes.palette(theme, dark)
    return if (dark) palette.toDarkColorScheme() else palette.toLightColorScheme()
}

/** The money colours for [theme] in the requested mode ([AppTheme.DYNAMIC] uses Paper's). */
fun moneyColorsFor(theme: AppTheme, dark: Boolean): MoneyColors = ThemePalettes.palette(theme, dark).toMoneyColors()
