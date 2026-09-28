package io.github.matteopi.budgeting.backup

import io.github.matteopi.budgeting.data.settings.AppSettings
import java.time.DayOfWeek

/**
 * Converts [AppSettings] (privacy/reminders/onboarding — app-only, not part of
 * `core.backup.BackupData`) to/from the `appSettings` string map a
 * [io.github.matteopi.budgeting.core.backup.BackupV1] carries. `lastSnapshotDate`/`lastExportDate`
 * are device-local bookkeeping and are intentionally not round-tripped: [decode] always keeps
 * the current device's values for them.
 */
object AppSettingsCodec {
    private const val HIDE_WIDGET_AMOUNTS = "hideWidgetAmounts"
    private const val APP_LOCK_ENABLED = "appLockEnabled"
    private const val SECURE_SCREEN = "secureScreen"
    private const val RECONCILE_REMINDER_ENABLED = "reconcileReminderEnabled"
    private const val RECONCILE_REMINDER_DAY = "reconcileReminderDay"
    private const val ONBOARDING_DONE = "onboardingDone"

    fun encode(settings: AppSettings): Map<String, String> = mapOf(
        HIDE_WIDGET_AMOUNTS to settings.hideWidgetAmounts.toString(),
        APP_LOCK_ENABLED to settings.appLockEnabled.toString(),
        SECURE_SCREEN to settings.secureScreen.toString(),
        RECONCILE_REMINDER_ENABLED to settings.reconcileReminderEnabled.toString(),
        RECONCILE_REMINDER_DAY to settings.reconcileReminderDay.name,
        ONBOARDING_DONE to settings.onboardingDone.toString(),
    )

    /** Applies [map] onto [current]; a missing or unreadable key falls back to [current]'s value. */
    fun decode(map: Map<String, String>, current: AppSettings): AppSettings = AppSettings(
        hideWidgetAmounts = map[HIDE_WIDGET_AMOUNTS]?.toBooleanStrictOrNull() ?: current.hideWidgetAmounts,
        appLockEnabled = map[APP_LOCK_ENABLED]?.toBooleanStrictOrNull() ?: current.appLockEnabled,
        secureScreen = map[SECURE_SCREEN]?.toBooleanStrictOrNull() ?: current.secureScreen,
        reconcileReminderEnabled = map[RECONCILE_REMINDER_ENABLED]?.toBooleanStrictOrNull()
            ?: current.reconcileReminderEnabled,
        reconcileReminderDay = map[RECONCILE_REMINDER_DAY]?.let { name -> runCatching { DayOfWeek.valueOf(name) }.getOrNull() }
            ?: current.reconcileReminderDay,
        onboardingDone = map[ONBOARDING_DONE]?.toBooleanStrictOrNull() ?: current.onboardingDone,
        lastSnapshotDate = current.lastSnapshotDate,
        lastExportDate = current.lastExportDate,
    )
}
