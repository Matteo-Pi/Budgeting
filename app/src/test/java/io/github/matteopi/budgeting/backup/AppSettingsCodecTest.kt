package io.github.matteopi.budgeting.backup

import io.github.matteopi.budgeting.data.settings.AppSettings
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
    }
}
