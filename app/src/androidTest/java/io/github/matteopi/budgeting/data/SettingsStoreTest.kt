package io.github.matteopi.budgeting.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.AppSettings
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.testing.DataTestSupport
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [SettingsStore] (Preferences DataStore) on a real device: defaults, round trips and tolerance of bad stored values. */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val store: SettingsStore get() = DataTestSupport.container.settingsStore

    private suspend fun budget(): BudgetSettings = store.budgetSettings.first()

    private suspend fun app(): AppSettings = store.appSettings.first()

    // ---- writing raw values, to simulate a file written by another version of the app -----------

    /** The store's DataStore. It has no raw-write hook, so the private field is read reflectively. */
    @Suppress("UNCHECKED_CAST")
    private fun rawDataStore(): DataStore<Preferences> {
        val field = SettingsStore::class.java.getDeclaredField("dataStore")
        field.isAccessible = true
        return field.get(store) as DataStore<Preferences>
    }

    private suspend fun putRawString(key: String, value: String) {
        rawDataStore().edit { it[stringPreferencesKey(key)] = value }
    }

    private suspend fun putRawLong(key: String, value: Long) {
        rawDataStore().edit { it[longPreferencesKey(key)] = value }
    }

    /** Every test starts from a fresh install even where the orchestrator's clearPackageData is not in use. */
    @Before
    fun startFromDefaults() = runBlocking<Unit> {
        rawDataStore().edit { it.clear() }
    }

    // ---- defaults -------------------------------------------------------------------------------

    @Test
    fun aFreshInstallHasTheDefaultBudgetSettings() = runBlocking<Unit> {
        val settings = budget()

        assertEquals(ZeroPoint.ACCOUNT_BALANCE, settings.zeroPoint)
        assertEquals(Money.pounds(100), settings.buffer)
        assertNull(settings.defaultAccountId)
        assertNull(settings.historyStartDate)
        assertEquals(1_000, settings.simulations)
        assertEquals(182, settings.forecastHorizonDays)
        assertEquals(BudgetSettings(), settings)
    }

    @Test
    fun aFreshInstallHasTheDefaultAppSettings() = runBlocking<Unit> {
        val settings = app()

        assertFalse(settings.hideWidgetAmounts)
        assertFalse(settings.appLockEnabled)
        assertFalse(settings.secureScreen)
        assertFalse(settings.reconcileReminderEnabled)
        assertEquals(DayOfWeek.SUNDAY, settings.reconcileReminderDay)
        assertFalse(settings.onboardingDone)
        assertNull(settings.lastSnapshotDate)
        assertNull(settings.lastExportDate)
        assertEquals(AppTheme.PAPER, settings.theme)
        assertEquals(DarkMode.SYSTEM, settings.darkMode)
        assertEquals(AccountView.Total, settings.chartAccountView)
        assertEquals(AccountView.Total, settings.balanceView)
        assertEquals(AppSettings(), settings)
    }

    // ---- budget setting round trips -----------------------------------------------------------

    @Test
    fun zeroPointRoundTrips() = runBlocking<Unit> {
        for (zeroPoint in ZeroPoint.values()) {
            store.setZeroPoint(zeroPoint)
            assertEquals(zeroPoint, budget().zeroPoint)
        }
    }

    @Test
    fun bufferRoundTrips() = runBlocking<Unit> {
        for (pence in listOf(0L, 1L, 10_000L, 25_050L, 123_456_789L)) {
            store.setBuffer(Money(pence))
            assertEquals(Money(pence), budget().buffer)
        }
    }

    @Test
    fun defaultAccountRoundTripsAndCanBeCleared() = runBlocking<Unit> {
        store.setDefaultAccountId(42L)
        assertEquals(42L, budget().defaultAccountId)

        store.setDefaultAccountId(7L)
        assertEquals(7L, budget().defaultAccountId)

        store.setDefaultAccountId(null)
        assertNull(budget().defaultAccountId)
    }

    @Test
    fun historyStartRoundTripsAndCanBeCleared() = runBlocking<Unit> {
        val date = LocalDate.of(2025, 9, 1)
        store.setHistoryStartDate(date)
        assertEquals(date, budget().historyStartDate)

        val later = LocalDate.of(2026, 1, 31)
        store.setHistoryStartDate(later)
        assertEquals(later, budget().historyStartDate)

        store.setHistoryStartDate(null)
        assertNull(budget().historyStartDate)
    }

    @Test
    fun simulationsAndHorizonRoundTrip() = runBlocking<Unit> {
        for (simulations in listOf(500, 1_000, 2_000)) {
            store.setSimulations(simulations)
            assertEquals(simulations, budget().simulations)
        }
        for (days in listOf(91, 182, 364)) {
            store.setForecastHorizonDays(days)
            assertEquals(days, budget().forecastHorizonDays)
        }
    }

    @Test
    fun replaceBudgetSettingsOverwritesEveryBudgetSetting() = runBlocking<Unit> {
        store.setZeroPoint(ZeroPoint.OVERDRAFT_LIMIT)
        store.setBuffer(Money.pounds(500))
        store.setDefaultAccountId(3L)
        store.setHistoryStartDate(LocalDate.of(2025, 1, 1))
        store.setSimulations(2_000)
        store.setForecastHorizonDays(364)

        val replacement = BudgetSettings(
            zeroPoint = ZeroPoint.ACCOUNT_BALANCE,
            buffer = Money(2_550),
            defaultAccountId = null,
            historyStartDate = null,
            simulations = 500,
            forecastHorizonDays = 91,
        )
        store.replaceBudgetSettings(replacement)
        assertEquals(replacement, budget())

        val full = BudgetSettings(
            zeroPoint = ZeroPoint.OVERDRAFT_LIMIT,
            buffer = Money.pounds(75),
            defaultAccountId = 11L,
            historyStartDate = LocalDate.of(2026, 2, 3),
            simulations = 1_000,
            forecastHorizonDays = 182,
        )
        store.replaceBudgetSettings(full)
        assertEquals(full, budget())
    }

    @Test
    fun replaceBudgetSettingsLeavesAppSettingsAlone() = runBlocking<Unit> {
        store.setTheme(AppTheme.SAGE)
        store.setHideWidgetAmounts(true)
        val before = app()

        store.replaceBudgetSettings(BudgetSettings(buffer = Money.pounds(1), simulations = 500))

        assertEquals(before, app())
    }

    // ---- app setting round trips --------------------------------------------------------------

    @Test
    fun privacyTogglesRoundTrip() = runBlocking<Unit> {
        store.setHideWidgetAmounts(true)
        assertTrue(app().hideWidgetAmounts)
        store.setHideWidgetAmounts(false)
        assertFalse(app().hideWidgetAmounts)

        store.setAppLockEnabled(true)
        assertTrue(app().appLockEnabled)
        store.setAppLockEnabled(false)
        assertFalse(app().appLockEnabled)

        store.setSecureScreen(true)
        assertTrue(app().secureScreen)
        store.setSecureScreen(false)
        assertFalse(app().secureScreen)
    }

    @Test
    fun reminderSettingsRoundTrip() = runBlocking<Unit> {
        store.setReconcileReminderEnabled(true)
        assertTrue(app().reconcileReminderEnabled)
        for (day in DayOfWeek.values()) {
            store.setReconcileReminderDay(day)
            assertEquals(day, app().reconcileReminderDay)
        }
        store.setReconcileReminderEnabled(false)
        assertFalse(app().reconcileReminderEnabled)
    }

    @Test
    fun onboardingFlagRoundTrips() = runBlocking<Unit> {
        store.setOnboardingDone(true)
        assertTrue(app().onboardingDone)
        store.setOnboardingDone(false)
        assertFalse(app().onboardingDone)
    }

    @Test
    fun snapshotAndExportDatesRoundTripAndCanBeCleared() = runBlocking<Unit> {
        val snapshot = LocalDate.of(2026, 9, 28)
        val export = LocalDate.of(2026, 9, 1)

        store.setLastSnapshotDate(snapshot)
        store.setLastExportDate(export)
        assertEquals(snapshot, app().lastSnapshotDate)
        assertEquals(export, app().lastExportDate)

        store.setLastSnapshotDate(null)
        assertNull(app().lastSnapshotDate)
        assertEquals(export, app().lastExportDate, "the other date is untouched")
        store.setLastExportDate(null)
        assertNull(app().lastExportDate)
    }

    @Test
    fun everyThemeAndDarkModeRoundTrips() = runBlocking<Unit> {
        for (theme in AppTheme.entries) {
            store.setTheme(theme)
            assertEquals(theme, app().theme)
        }
        for (mode in DarkMode.entries) {
            store.setDarkMode(mode)
            assertEquals(mode, app().darkMode)
        }
    }

    @Test
    fun chartAccountViewRoundTrips() = runBlocking<Unit> {
        for (view in listOf(AccountView.Each, AccountView.Single(12L), AccountView.Total)) {
            store.setChartAccountView(view)
            assertEquals(view, app().chartAccountView)
        }
    }

    @Test
    fun balanceViewRoundTripsAndNeverEndsUpAsEach() = runBlocking<Unit> {
        store.setBalanceView(AccountView.Single(7L))
        assertEquals(AccountView.Single(7L), app().balanceView)
        store.setBalanceView(AccountView.Total)
        assertEquals(AccountView.Total, app().balanceView)

        // "Each account" makes no sense for a single number, so it reads back as the total.
        store.setBalanceView(AccountView.Each)
        assertEquals(AccountView.Total, app().balanceView)
    }

    @Test
    fun settersDoNotDisturbEachOther() = runBlocking<Unit> {
        store.setZeroPoint(ZeroPoint.OVERDRAFT_LIMIT)
        store.setBuffer(Money.pounds(250))
        store.setDefaultAccountId(5L)
        store.setHistoryStartDate(LocalDate.of(2025, 10, 6))
        store.setSimulations(2_000)
        store.setForecastHorizonDays(91)
        store.setHideWidgetAmounts(true)
        store.setAppLockEnabled(true)
        store.setSecureScreen(true)
        store.setReconcileReminderEnabled(true)
        store.setReconcileReminderDay(DayOfWeek.FRIDAY)
        store.setOnboardingDone(true)
        store.setLastSnapshotDate(LocalDate.of(2026, 9, 28))
        store.setLastExportDate(LocalDate.of(2026, 9, 20))
        store.setTheme(AppTheme.INK)
        store.setDarkMode(DarkMode.LIGHT)
        store.setChartAccountView(AccountView.Each)
        store.setBalanceView(AccountView.Single(5L))

        assertEquals(
            BudgetSettings(
                zeroPoint = ZeroPoint.OVERDRAFT_LIMIT,
                buffer = Money.pounds(250),
                defaultAccountId = 5L,
                historyStartDate = LocalDate.of(2025, 10, 6),
                simulations = 2_000,
                forecastHorizonDays = 91,
            ),
            budget(),
        )
        val expectedApp = AppSettings(
            hideWidgetAmounts = true,
            appLockEnabled = true,
            secureScreen = true,
            reconcileReminderEnabled = true,
            reconcileReminderDay = DayOfWeek.FRIDAY,
            onboardingDone = true,
            lastSnapshotDate = LocalDate.of(2026, 9, 28),
            lastExportDate = LocalDate.of(2026, 9, 20),
            theme = AppTheme.INK,
            darkMode = DarkMode.LIGHT,
            chartAccountView = AccountView.Each,
            balanceView = AccountView.Single(5L),
        )
        assertEquals(expectedApp, app())

        // Changing one setting changes only that setting.
        store.setTheme(AppTheme.MONO)
        assertEquals(expectedApp.copy(theme = AppTheme.MONO), app())
        store.setBuffer(Money.pounds(10))
        assertEquals(Money.pounds(10), budget().buffer)
        assertEquals(expectedApp.copy(theme = AppTheme.MONO), app())
    }

    @Test
    fun anActiveCollectorSeesChangesAsTheyHappen() = runBlocking<Unit> {
        val seen = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) { store.budgetSettings.first { it.simulations == 2_000 && it.buffer == Money(999) } }
        }

        store.setSimulations(2_000)
        store.setBuffer(Money(999))

        val settings = seen.await()
        assertEquals(2_000, settings.simulations)
        assertEquals(Money(999), settings.buffer)
    }

    // ---- bad stored values --------------------------------------------------------------------

    @Test
    fun invalidStoredChartViewsFallBackToTotal() = runBlocking<Unit> {
        for (bad in listOf("banana", "", "account:", "account:abc", "account:3;extra", "EACH", "Total")) {
            putRawString("chart_account_view", bad)
            assertEquals(AccountView.Total, app().chartAccountView, "stored \"$bad\"")
        }
    }

    @Test
    fun invalidStoredBalanceViewsFallBackToTotal() = runBlocking<Unit> {
        // "each" is a valid encoding, but not for the balance view.
        for (bad in listOf("banana", "", "account:", "account:abc", "each")) {
            putRawString("balance_view", bad)
            assertEquals(AccountView.Total, app().balanceView, "stored \"$bad\"")
        }
    }

    @Test
    fun validStoredViewsAreReadBack() = runBlocking<Unit> {
        putRawString("chart_account_view", "each")
        putRawString("balance_view", "account:9")

        assertEquals(AccountView.Each, app().chartAccountView)
        assertEquals(AccountView.Single(9L), app().balanceView)
    }

    @Test
    fun unknownStoredEnumsFallBackToTheirDefaults() = runBlocking<Unit> {
        putRawString("zero_point", "NOT_A_ZERO_POINT")
        putRawString("app_theme", "NEON")
        putRawString("dark_mode", "PURPLE")
        putRawString("reconcile_reminder_dow", "FUNDAY")

        assertEquals(ZeroPoint.ACCOUNT_BALANCE, budget().zeroPoint)
        assertEquals(AppTheme.PAPER, app().theme)
        assertEquals(DarkMode.SYSTEM, app().darkMode)
        assertEquals(DayOfWeek.SUNDAY, app().reconcileReminderDay)
    }

    @Test
    fun negativeStoredIdsAndDatesReadAsUnset() = runBlocking<Unit> {
        putRawLong("default_account_id", -5L)
        putRawLong("history_start_epoch_day", -1L)
        putRawLong("last_snapshot_epoch_day", -1L)
        putRawLong("last_export_epoch_day", -1L)

        assertNull(budget().defaultAccountId)
        assertNull(budget().historyStartDate)
        assertNull(app().lastSnapshotDate)
        assertNull(app().lastExportDate)
    }
}
