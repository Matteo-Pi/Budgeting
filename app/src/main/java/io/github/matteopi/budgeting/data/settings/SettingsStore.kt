package io.github.matteopi.budgeting.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val DEFAULT_BUDGET_SETTINGS = BudgetSettings()

/** Preferences DataStore named "settings". See docs/PLAN.md §6.3 for the key list. */
class SettingsStore(context: Context) {
    private val dataStore = context.settingsDataStore

    val budgetSettings: Flow<BudgetSettings> = dataStore.data.map { prefs ->
        BudgetSettings(
            zeroPoint = prefs[Keys.ZERO_POINT]?.let { runCatching { ZeroPoint.valueOf(it) }.getOrNull() }
                ?: DEFAULT_BUDGET_SETTINGS.zeroPoint,
            buffer = Money(prefs[Keys.BUFFER_PENCE] ?: DEFAULT_BUDGET_SETTINGS.buffer.pence),
            defaultAccountId = prefs[Keys.DEFAULT_ACCOUNT_ID]?.takeIf { it >= 0 },
            historyStartDate = prefs[Keys.HISTORY_START_EPOCH_DAY]?.takeIf { it >= 0 }?.let(LocalDate::ofEpochDay),
            simulations = prefs[Keys.SIMULATIONS] ?: DEFAULT_BUDGET_SETTINGS.simulations,
            forecastHorizonDays = prefs[Keys.HORIZON_DAYS] ?: DEFAULT_BUDGET_SETTINGS.forecastHorizonDays,
        )
    }

    val appSettings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            hideWidgetAmounts = prefs[Keys.HIDE_WIDGET_AMOUNTS] ?: false,
            appLockEnabled = prefs[Keys.APP_LOCK] ?: false,
            secureScreen = prefs[Keys.SECURE_SCREEN] ?: false,
            reconcileReminderEnabled = prefs[Keys.RECONCILE_REMINDER] ?: false,
            reconcileReminderDay = prefs[Keys.RECONCILE_REMINDER_DOW]
                ?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } ?: DayOfWeek.SUNDAY,
            onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
            lastSnapshotDate = prefs[Keys.LAST_SNAPSHOT_EPOCH_DAY]?.takeIf { it >= 0 }?.let(LocalDate::ofEpochDay),
            lastExportDate = prefs[Keys.LAST_EXPORT_EPOCH_DAY]?.takeIf { it >= 0 }?.let(LocalDate::ofEpochDay),
            theme = AppTheme.parse(prefs[Keys.APP_THEME]) ?: AppTheme.DEFAULT,
            darkMode = DarkMode.parse(prefs[Keys.DARK_MODE]) ?: DarkMode.DEFAULT,
            chartAccountView = AccountView.parseChart(prefs[Keys.CHART_ACCOUNT_VIEW]),
            balanceView = AccountView.parseBalance(prefs[Keys.BALANCE_VIEW]),
        )
    }

    suspend fun setZeroPoint(zeroPoint: ZeroPoint) {
        dataStore.edit { it[Keys.ZERO_POINT] = zeroPoint.name }
    }

    suspend fun setBuffer(buffer: Money) {
        dataStore.edit { it[Keys.BUFFER_PENCE] = buffer.pence }
    }

    suspend fun setDefaultAccountId(accountId: Long?) {
        dataStore.edit { it[Keys.DEFAULT_ACCOUNT_ID] = accountId ?: -1L }
    }

    suspend fun setHistoryStartDate(date: LocalDate?) {
        dataStore.edit { it[Keys.HISTORY_START_EPOCH_DAY] = date?.toEpochDay() ?: -1L }
    }

    suspend fun setSimulations(simulations: Int) {
        dataStore.edit { it[Keys.SIMULATIONS] = simulations }
    }

    suspend fun setForecastHorizonDays(days: Int) {
        dataStore.edit { it[Keys.HORIZON_DAYS] = days }
    }

    /** Overwrites every calculation-affecting setting at once (used by backup restore). */
    suspend fun replaceBudgetSettings(settings: BudgetSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.ZERO_POINT] = settings.zeroPoint.name
            prefs[Keys.BUFFER_PENCE] = settings.buffer.pence
            prefs[Keys.DEFAULT_ACCOUNT_ID] = settings.defaultAccountId ?: -1L
            prefs[Keys.HISTORY_START_EPOCH_DAY] = settings.historyStartDate?.toEpochDay() ?: -1L
            prefs[Keys.SIMULATIONS] = settings.simulations
            prefs[Keys.HORIZON_DAYS] = settings.forecastHorizonDays
        }
    }

    suspend fun setHideWidgetAmounts(hide: Boolean) {
        dataStore.edit { it[Keys.HIDE_WIDGET_AMOUNTS] = hide }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.APP_LOCK] = enabled }
    }

    suspend fun setSecureScreen(enabled: Boolean) {
        dataStore.edit { it[Keys.SECURE_SCREEN] = enabled }
    }

    suspend fun setReconcileReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RECONCILE_REMINDER] = enabled }
    }

    suspend fun setReconcileReminderDay(day: DayOfWeek) {
        dataStore.edit { it[Keys.RECONCILE_REMINDER_DOW] = day.name }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setLastSnapshotDate(date: LocalDate?) {
        dataStore.edit { it[Keys.LAST_SNAPSHOT_EPOCH_DAY] = date?.toEpochDay() ?: -1L }
    }

    suspend fun setLastExportDate(date: LocalDate?) {
        dataStore.edit { it[Keys.LAST_EXPORT_EPOCH_DAY] = date?.toEpochDay() ?: -1L }
    }

    suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { it[Keys.APP_THEME] = theme.name }
    }

    suspend fun setDarkMode(mode: DarkMode) {
        dataStore.edit { it[Keys.DARK_MODE] = mode.name }
    }

    suspend fun setChartAccountView(view: AccountView) {
        dataStore.edit { it[Keys.CHART_ACCOUNT_VIEW] = AccountView.encode(view) }
    }

    suspend fun setBalanceView(view: AccountView) {
        dataStore.edit { it[Keys.BALANCE_VIEW] = AccountView.encode(view) }
    }

    private object Keys {
        val ZERO_POINT = stringPreferencesKey("zero_point")
        val BUFFER_PENCE = longPreferencesKey("buffer_pence")
        val DEFAULT_ACCOUNT_ID = longPreferencesKey("default_account_id")
        val HISTORY_START_EPOCH_DAY = longPreferencesKey("history_start_epoch_day")
        val SIMULATIONS = intPreferencesKey("simulations")
        val HORIZON_DAYS = intPreferencesKey("horizon_days")
        val HIDE_WIDGET_AMOUNTS = booleanPreferencesKey("hide_widget_amounts")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        val RECONCILE_REMINDER = booleanPreferencesKey("reconcile_reminder")
        val RECONCILE_REMINDER_DOW = stringPreferencesKey("reconcile_reminder_dow")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val LAST_SNAPSHOT_EPOCH_DAY = longPreferencesKey("last_snapshot_epoch_day")
        val LAST_EXPORT_EPOCH_DAY = longPreferencesKey("last_export_epoch_day")
        val APP_THEME = stringPreferencesKey("app_theme")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val CHART_ACCOUNT_VIEW = stringPreferencesKey("chart_account_view")
        val BALANCE_VIEW = stringPreferencesKey("balance_view")
    }
}
