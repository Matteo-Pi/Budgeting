package io.github.matteopi.budgeting.backup

import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.AppSettings
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsCodecTest {
    @Test
    fun `round trips every encoded field`() {
        val settings = AppSettings(
            hideWidgetAmounts = true,
            appLockEnabled = true,
            secureScreen = true,
            reconcileReminderEnabled = true,
            reconcileReminderDay = DayOfWeek.WEDNESDAY,
            onboardingDone = true,
            theme = AppTheme.SLATE,
            darkMode = DarkMode.DARK,
            chartAccountView = AccountView.Single(12),
            balanceView = AccountView.Single(7),
            lastSnapshotDate = LocalDate.of(2026, 9, 1),
            lastExportDate = LocalDate.of(2026, 9, 20),
        )
        val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(settings), current = AppSettings())
        assertEquals(settings.hideWidgetAmounts, decoded.hideWidgetAmounts)
        assertEquals(settings.appLockEnabled, decoded.appLockEnabled)
        assertEquals(settings.secureScreen, decoded.secureScreen)
        assertEquals(settings.reconcileReminderEnabled, decoded.reconcileReminderEnabled)
        assertEquals(settings.reconcileReminderDay, decoded.reconcileReminderDay)
        assertEquals(settings.onboardingDone, decoded.onboardingDone)
        assertEquals(settings.theme, decoded.theme)
        assertEquals(settings.darkMode, decoded.darkMode)
        assertEquals(settings.chartAccountView, decoded.chartAccountView)
        assertEquals(settings.balanceView, decoded.balanceView)
    }

    @Test
    fun `every theme and dark mode survives a round trip`() {
        for (theme in AppTheme.entries) {
            for (mode in DarkMode.entries) {
                val decoded = AppSettingsCodec.decode(
                    AppSettingsCodec.encode(AppSettings(theme = theme, darkMode = mode)),
                    current = AppSettings(theme = AppTheme.PAPER, darkMode = DarkMode.SYSTEM),
                )
                assertEquals(theme, decoded.theme)
                assertEquals(mode, decoded.darkMode)
            }
        }
    }

    @Test
    fun `encodes the theme and dark mode by enum name`() {
        val encoded = AppSettingsCodec.encode(AppSettings(theme = AppTheme.INK, darkMode = DarkMode.LIGHT))
        assertEquals("INK", encoded["theme"])
        assertEquals("LIGHT", encoded["darkMode"])
    }

    @Test
    fun `an unknown theme or dark mode keeps the current value`() {
        val current = AppSettings(theme = AppTheme.SAGE, darkMode = DarkMode.DARK)
        val decoded = AppSettingsCodec.decode(mapOf("theme" to "NEON", "darkMode" to "sometimes"), current)
        assertEquals(AppTheme.SAGE, decoded.theme)
        assertEquals(DarkMode.DARK, decoded.darkMode)
    }

    @Test
    fun `a backup from before themes existed leaves the current theme alone`() {
        val current = AppSettings(theme = AppTheme.MONO, darkMode = DarkMode.LIGHT)
        val decoded = AppSettingsCodec.decode(mapOf("hideWidgetAmounts" to "true"), current)
        assertEquals(true, decoded.hideWidgetAmounts)
        assertEquals(AppTheme.MONO, decoded.theme)
        assertEquals(DarkMode.LIGHT, decoded.darkMode)
    }

    @Test
    fun `device-local dates are never taken from the backup`() {
        val backupSettings = AppSettings(lastSnapshotDate = LocalDate.of(2020, 1, 1), lastExportDate = LocalDate.of(2020, 1, 1))
        val current = AppSettings(lastSnapshotDate = LocalDate.of(2026, 9, 28), lastExportDate = null)
        val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(backupSettings), current = current)
        assertEquals(current.lastSnapshotDate, decoded.lastSnapshotDate)
        assertEquals(current.lastExportDate, decoded.lastExportDate)
    }

    @Test
    fun `missing keys fall back to the current value`() {
        val current = AppSettings(hideWidgetAmounts = true, reconcileReminderDay = DayOfWeek.FRIDAY)
        val decoded = AppSettingsCodec.decode(emptyMap(), current)
        assertEquals(current.hideWidgetAmounts, decoded.hideWidgetAmounts)
        assertEquals(current.reconcileReminderDay, decoded.reconcileReminderDay)
        assertEquals(current.theme, decoded.theme)
        assertEquals(current.darkMode, decoded.darkMode)
    }

    @Test
    fun `account views are encoded as all, pool, each and account colon id`() {
        val encoded = AppSettingsCodec.encode(
            AppSettings(chartAccountView = AccountView.Each, balanceView = AccountView.Single(12)),
        )
        assertEquals("each", encoded["chartAccountView"])
        assertEquals("account:12", encoded["balanceView"])
        val defaults = AppSettingsCodec.encode(AppSettings())
        assertEquals("all", defaults["chartAccountView"])
        assertEquals("all", defaults["balanceView"])
        val pool = AppSettingsCodec.encode(AppSettings(chartAccountView = AccountView.Pool, balanceView = AccountView.Pool))
        assertEquals("pool", pool["chartAccountView"])
        assertEquals("pool", pool["balanceView"])
    }

    @Test
    fun `a backup written with the legacy total value restores as all accounts`() {
        val decoded = AppSettingsCodec.decode(
            mapOf("chartAccountView" to "total", "balanceView" to "total"),
            AppSettings(chartAccountView = AccountView.Each, balanceView = AccountView.Single(2)),
        )
        assertEquals(AccountView.All, decoded.chartAccountView)
        assertEquals(AccountView.All, decoded.balanceView)
    }

    @Test
    fun `every chart account view survives a round trip`() {
        for (view in listOf(AccountView.All, AccountView.Pool, AccountView.Each, AccountView.Single(3))) {
            val decoded = AppSettingsCodec.decode(
                AppSettingsCodec.encode(AppSettings(chartAccountView = view)),
                current = AppSettings(chartAccountView = AccountView.Single(99)),
            )
            assertEquals(view, decoded.chartAccountView)
        }
    }

    @Test
    fun `an unreadable account view keeps the current value`() {
        val current = AppSettings(chartAccountView = AccountView.Each, balanceView = AccountView.Single(2))
        val decoded = AppSettingsCodec.decode(mapOf("chartAccountView" to "sideways", "balanceView" to "account:abc"), current)
        assertEquals(AccountView.Each, decoded.chartAccountView)
        assertEquals(AccountView.Single(2), decoded.balanceView)
    }

    @Test
    fun `a balance view can never be each`() {
        val decoded = AppSettingsCodec.decode(mapOf("balanceView" to "each"), AppSettings(balanceView = AccountView.Single(2)))
        assertEquals(AccountView.All, decoded.balanceView)
    }

    @Test
    fun `a backup from before account views existed leaves the current views alone`() {
        val current = AppSettings(chartAccountView = AccountView.Each, balanceView = AccountView.Single(4))
        val decoded = AppSettingsCodec.decode(mapOf("hideWidgetAmounts" to "false"), current)
        assertEquals(AccountView.Each, decoded.chartAccountView)
        assertEquals(AccountView.Single(4), decoded.balanceView)
    }
}
