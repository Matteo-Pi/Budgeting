package io.github.matteopi.budgeting.ui.theme

import io.github.matteopi.budgeting.data.settings.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemePalettesTest {
    private val allPalettes: List<Triple<AppTheme, Boolean, PaletteSpec>> =
        AppTheme.entries.flatMap { theme ->
            listOf(false, true).map { dark -> Triple(theme, dark, ThemePalettes.palette(theme, dark)) }
        }

    private fun label(theme: AppTheme, dark: Boolean) = "${theme.name} ${if (dark) "dark" else "light"}"

    /** Every surface a text or accent colour can land on: the page, cards and the container tones. */
    private fun surfaces(p: PaletteSpec): Map<String, Long> = mapOf(
        "background" to p.background,
        "surface" to p.surface,
        "surfaceVariant" to p.surfaceVariant,
        "surfaceContainerLow" to p.surfaceContainerLow,
        "surfaceContainer" to p.surfaceContainer,
        "surfaceContainerHigh" to p.surfaceContainerHigh,
        "surfaceContainerHighest" to p.surfaceContainerHighest,
    )

    private fun assertContrast(minimum: Double, foreground: Long, background: Long, what: String) {
        val ratio = ColorContrast.ratio(foreground, background)
        assertTrue(ratio >= minimum, "$what: contrast ${"%.2f".format(ratio)} is below $minimum")
    }

    @Test
    fun `contrast helper matches known WCAG values`() {
        assertEquals(21.0, ColorContrast.ratio(0xFF000000, 0xFFFFFFFF), 1e-9)
        assertEquals(1.0, ColorContrast.ratio(0xFF336699, 0xFF336699), 1e-9)
        assertEquals(ColorContrast.ratio(0xFF111111, 0xFFFAF9F6), ColorContrast.ratio(0xFFFAF9F6, 0xFF111111), 1e-12)
        // #767676 on white is the classic "just passes AA" grey (4.54:1).
        assertEquals(4.54, ColorContrast.ratio(0xFF767676, 0xFFFFFFFF), 0.01)
        assertEquals(0.0, ColorContrast.relativeLuminance(0xFF000000), 1e-12)
        assertEquals(1.0, ColorContrast.relativeLuminance(0xFFFFFFFF), 1e-12)
    }

    @Test
    fun `text on the background and primary containers meets AA in every theme and mode`() {
        for ((theme, dark, p) in allPalettes) {
            val name = label(theme, dark)
            assertContrast(4.5, p.onBackground, p.background, "$name onBackground/background")
            assertContrast(4.5, p.onPrimary, p.primary, "$name onPrimary/primary")
        }
    }

    @Test
    fun `body and muted text meet AA on every surface and card tone`() {
        for ((theme, dark, p) in allPalettes) {
            for ((surfaceName, surface) in surfaces(p)) {
                val name = label(theme, dark)
                assertContrast(4.5, p.onSurface, surface, "$name onSurface/$surfaceName")
                assertContrast(4.5, p.onSurfaceVariant, surface, "$name onSurfaceVariant/$surfaceName")
            }
        }
    }

    @Test
    fun `accent colours are legible as text and lines on every surface`() {
        for ((theme, dark, p) in allPalettes) {
            for ((surfaceName, surface) in surfaces(p)) {
                val name = label(theme, dark)
                // Text buttons and links use primary directly on cards, so it gets the text threshold.
                assertContrast(4.5, p.primary, surface, "$name primary/$surfaceName")
                assertContrast(3.0, p.tertiary, surface, "$name tertiary/$surfaceName")
                assertContrast(4.5, p.error, surface, "$name error/$surfaceName")
            }
        }
    }

    @Test
    fun `content colours meet AA on their containers`() {
        for ((theme, dark, p) in allPalettes) {
            val name = label(theme, dark)
            assertContrast(4.5, p.onPrimaryContainer, p.primaryContainer, "$name onPrimaryContainer")
            assertContrast(4.5, p.onSecondary, p.secondary, "$name onSecondary")
            assertContrast(4.5, p.onSecondaryContainer, p.secondaryContainer, "$name onSecondaryContainer")
            assertContrast(4.5, p.onTertiary, p.tertiary, "$name onTertiary")
            assertContrast(4.5, p.onTertiaryContainer, p.tertiaryContainer, "$name onTertiaryContainer")
            assertContrast(4.5, p.onError, p.error, "$name onError")
            assertContrast(4.5, p.onErrorContainer, p.errorContainer, "$name onErrorContainer")
            assertContrast(4.5, p.inverseOnSurface, p.inverseSurface, "$name snackbar text")
            assertContrast(3.0, p.inversePrimary, p.inverseSurface, "$name snackbar action")
        }
    }

    @Test
    fun `money colours are legible on every surface`() {
        for ((theme, dark, p) in allPalettes) {
            for ((surfaceName, surface) in surfaces(p)) {
                val name = label(theme, dark)
                assertContrast(4.5, p.income, surface, "$name income/$surfaceName")
                assertContrast(4.5, p.negative, surface, "$name negative/$surfaceName")
            }
        }
    }

    @Test
    fun `chart buffer amber is visible on every surface`() {
        for ((theme, dark, p) in allPalettes) {
            val amber = if (dark) ThemePalettes.DARK_WARNING else ThemePalettes.LIGHT_WARNING
            for ((surfaceName, surface) in surfaces(p)) {
                assertContrast(3.0, amber, surface, "${label(theme, dark)} amber/$surfaceName")
            }
        }
    }

    @Test
    fun `containers stay only a shade away from the background`() {
        for ((theme, dark, p) in allPalettes) {
            for ((surfaceName, surface) in surfaces(p)) {
                val ratio = ColorContrast.ratio(p.background, surface)
                assertTrue(ratio < 1.4, "${label(theme, dark)} $surfaceName is ${"%.2f".format(ratio)}:1 from the background")
            }
        }
    }

    @Test
    fun `every colour is fully opaque`() {
        for ((theme, dark, p) in allPalettes) {
            val values = mapOf(
                "primary" to p.primary, "onPrimary" to p.onPrimary, "primaryContainer" to p.primaryContainer,
                "onPrimaryContainer" to p.onPrimaryContainer, "inversePrimary" to p.inversePrimary,
                "secondary" to p.secondary, "onSecondary" to p.onSecondary, "secondaryContainer" to p.secondaryContainer,
                "onSecondaryContainer" to p.onSecondaryContainer, "tertiary" to p.tertiary, "onTertiary" to p.onTertiary,
                "tertiaryContainer" to p.tertiaryContainer, "onTertiaryContainer" to p.onTertiaryContainer,
                "background" to p.background, "onBackground" to p.onBackground, "surface" to p.surface,
                "onSurface" to p.onSurface, "surfaceVariant" to p.surfaceVariant, "onSurfaceVariant" to p.onSurfaceVariant,
                "outline" to p.outline, "outlineVariant" to p.outlineVariant, "error" to p.error, "onError" to p.onError,
                "errorContainer" to p.errorContainer, "onErrorContainer" to p.onErrorContainer,
                "surfaceDim" to p.surfaceDim, "surfaceBright" to p.surfaceBright,
                "surfaceContainerLowest" to p.surfaceContainerLowest, "surfaceContainerLow" to p.surfaceContainerLow,
                "surfaceContainer" to p.surfaceContainer, "surfaceContainerHigh" to p.surfaceContainerHigh,
                "surfaceContainerHighest" to p.surfaceContainerHighest, "inverseSurface" to p.inverseSurface,
                "inverseOnSurface" to p.inverseOnSurface, "income" to p.income, "negative" to p.negative,
            )
            for ((role, argb) in values) {
                assertEquals(0xFFL, (argb shr 24) and 0xFF, "${label(theme, dark)} $role is not opaque")
            }
        }
    }

    @Test
    fun `dark palettes are darker than their light counterparts`() {
        for (theme in AppTheme.entries) {
            val light = ColorContrast.relativeLuminance(ThemePalettes.palette(theme, dark = false).background)
            val dark = ColorContrast.relativeLuminance(ThemePalettes.palette(theme, dark = true).background)
            assertTrue(dark < 0.05 && light > 0.85, "${theme.name}: light $light, dark $dark")
        }
    }

    @Test
    fun `container tones step away from the background in order`() {
        for ((theme, dark, p) in allPalettes) {
            val tones = listOf(
                p.surfaceContainerLowest, p.surfaceContainerLow, p.surfaceContainer,
                p.surfaceContainerHigh, p.surfaceContainerHighest,
            ).map { ColorContrast.relativeLuminance(it) }
            // Light themes get darker from "lowest" to "highest"; dark themes get lighter.
            val expected = if (dark) tones.sorted() else tones.sortedDescending()
            assertEquals(expected, tones, "${label(theme, dark)} container tones are out of order")
        }
    }

    @Test
    fun `themes keep their starting-point identity`() {
        assertEquals(0xFFFAF9F6L, ThemePalettes.palette(AppTheme.PAPER, dark = false).background)
        assertEquals(0xFF141412L, ThemePalettes.palette(AppTheme.PAPER, dark = true).background)
        assertEquals(0xFF111111L, ThemePalettes.palette(AppTheme.MONO, dark = false).primary)
        assertEquals(0xFFF2F2F2L, ThemePalettes.palette(AppTheme.MONO, dark = true).primary)
        assertEquals(0xFFF7F8FAL, ThemePalettes.palette(AppTheme.SLATE, dark = false).background)
        assertEquals(0xFFF8F4EEL, ThemePalettes.palette(AppTheme.SAND, dark = false).background)
        assertEquals(0xFFF5F7F4L, ThemePalettes.palette(AppTheme.SAGE, dark = false).background)
        assertEquals(0xFFFFFFFFL, ThemePalettes.palette(AppTheme.INK, dark = false).background)
        assertEquals(0xFF0D1020L, ThemePalettes.palette(AppTheme.INK, dark = true).background)
        assertEquals(0xFFA9B4F0L, ThemePalettes.palette(AppTheme.INK, dark = true).primary)
    }

    @Test
    fun `dynamic falls back to Paper and every other theme is distinct`() {
        for (dark in listOf(false, true)) {
            assertEquals(ThemePalettes.palette(AppTheme.PAPER, dark), ThemePalettes.palette(AppTheme.DYNAMIC, dark))
            val concrete = AppTheme.entries.filter { it != AppTheme.DYNAMIC }
            val primaries = concrete.map { ThemePalettes.palette(it, dark).primary }
            assertEquals(primaries.size, primaries.toSet().size, "primary colours must differ between themes")
        }
        assertNotEquals(
            ThemePalettes.palette(AppTheme.PAPER, dark = false),
            ThemePalettes.palette(AppTheme.PAPER, dark = true),
        )
    }
}
