package io.github.matteopi.budgeting.ui.theme

import kotlin.math.pow

/**
 * One light or dark palette as plain `0xAARRGGBB` longs (no Compose types, so JVM unit tests can
 * read it). [toColorScheme] and [toMoneyColors] turn it into what the UI uses. The role names
 * follow Material 3's colour roles.
 *
 * [income] and [negative] are the "money" colours (see [MoneyColors]): legible on the background,
 * on `surface` and on the card container tones, and muted so they fit the palette.
 */
data class PaletteSpec(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val inversePrimary: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val outlineVariant: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val surfaceDim: Long,
    val surfaceBright: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val inverseSurface: Long,
    val inverseOnSurface: Long,
    val income: Long,
    val negative: Long,
)

/** WCAG 2.x relative luminance and contrast ratio for opaque `0xAARRGGBB` colours (alpha ignored). */
object ColorContrast {
    /** Relative luminance in 0.0 (black) to 1.0 (white). */
    fun relativeLuminance(argb: Long): Double {
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Contrast ratio between two colours, from 1.0 (identical) to 21.0 (black on white); order does not matter. */
    fun ratio(first: Long, second: Long): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun channel(value: Long): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
