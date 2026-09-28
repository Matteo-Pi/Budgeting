package io.github.matteopi.budgeting.data.settings

import java.time.DayOfWeek
import java.time.LocalDate

/** Privacy/UI-only settings. Calculation-affecting settings are [io.github.matteopi.budgeting.core.model.BudgetSettings] (core). */
data class AppSettings(
    val hideWidgetAmounts: Boolean = false,
    val appLockEnabled: Boolean = false,
    val secureScreen: Boolean = false,
    val reconcileReminderEnabled: Boolean = false,
    val reconcileReminderDay: DayOfWeek = DayOfWeek.SUNDAY,
    val onboardingDone: Boolean = false,
    val lastSnapshotDate: LocalDate? = null,
    val lastExportDate: LocalDate? = null,
    val theme: AppTheme = AppTheme.DEFAULT,
    val darkMode: DarkMode = DarkMode.DEFAULT,
    /** What the Forecast, Timeline and Insights charts show: the pool total, one account, or one line per account. */
    val chartAccountView: AccountView = AccountView.Total,
    /** What the balance number on Home shows: the pool total or one account (never [AccountView.Each]). */
    val balanceView: AccountView = AccountView.Total,
)
