package io.github.matteopi.budgeting.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThemeSettingsTest {
    @Test
    fun `defaults are Paper and follow the system`() {
        assertEquals(AppTheme.PAPER, AppSettings().theme)
        assertEquals(DarkMode.SYSTEM, AppSettings().darkMode)
        assertEquals(AppTheme.PAPER, AppTheme.DEFAULT)
        assertEquals(DarkMode.SYSTEM, DarkMode.DEFAULT)
    }

    @Test
    fun `every theme name parses back to itself`() {
        for (theme in AppTheme.entries) assertEquals(theme, AppTheme.parse(theme.name))
    }

    @Test
    fun `every dark mode name parses back to itself`() {
        for (mode in DarkMode.entries) assertEquals(mode, DarkMode.parse(mode.name))
    }

    @Test
    fun `unknown or missing theme names parse to null so callers fall back to the default`() {
        assertNull(AppTheme.parse(null))
        assertNull(AppTheme.parse(""))
        assertNull(AppTheme.parse("NEON"))
        assertNull(AppTheme.parse("paper"))
        assertEquals(AppTheme.PAPER, AppTheme.parse("NEON") ?: AppTheme.DEFAULT)
    }

    @Test
    fun `unknown or missing dark mode names parse to null so callers fall back to the default`() {
        assertNull(DarkMode.parse(null))
        assertNull(DarkMode.parse(""))
        assertNull(DarkMode.parse("AUTO"))
        assertNull(DarkMode.parse("dark"))
        assertEquals(DarkMode.SYSTEM, DarkMode.parse("AUTO") ?: DarkMode.DEFAULT)
    }

    @Test
    fun `theme order and labels are stable for the picker`() {
        assertEquals(
            listOf("Paper", "Mono", "Slate", "Sand", "Sage", "Ink", "Dynamic"),
            AppTheme.entries.map { it.label },
        )
        assertEquals(listOf("System", "Light", "Dark"), DarkMode.entries.map { it.label })
    }
}
