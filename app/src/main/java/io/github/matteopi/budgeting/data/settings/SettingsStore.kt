package io.github.matteopi.budgeting.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.freetime.FreeSettings
import io.github.matteopi.budgeting.core.planner.suggest.StudyPrefs
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val DEFAULT_BUDGET_SETTINGS = BudgetSettings()

/** The `appSettings` map entries a backup uses for the Planner's preferences. */
const val PLANNER_REMINDERS_BACKUP_KEY = "plannerReminders"
const val PLANNER_TEMPLATES_BACKUP_KEY = "plannerTemplates"
const val PLANNER_DAY_BACKUP_KEY = "plannerDay"
const val PLANNER_FREE_BACKUP_KEY = "plannerFree"
const val PLANNER_STUDY_BACKUP_KEY = "plannerStudy"
const val PLANNER_DAYS_OFF_BACKUP_KEY = "plannerDaysOff"

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

    // ----- App section (docs/PLAN.md §11.8). Not in AppSettings: it is not backed up. -----

    /** The section that opens next time: [AppSection.BUDGETING] until the user switches; unknown text reads as BUDGETING. */
    val appSection: Flow<AppSection> = dataStore.data.map { prefs ->
        AppSection.parse(prefs[SectionKeys.APP_SECTION])
    }

    suspend fun setAppSection(section: AppSection) {
        dataStore.edit { it[SectionKeys.APP_SECTION] = section.name }
    }

    private object SectionKeys {
        val APP_SECTION = stringPreferencesKey("app_section")
    }

    // ---- Recipes section (docs/PLAN.md §11.3). Not part of backups. -----------------------------

    /** Whether optional ingredient lines count in a recipe's cost and nutrition. Default true. */
    val countOptionalExtras: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FoodKeys.COUNT_OPTIONAL_EXTRAS] ?: true
    }

    /** Whether scanning a barcode may look it up on Open Food Facts (sends only the number). Default false. */
    val lookUpBarcodesOnline: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FoodKeys.LOOK_UP_BARCODES_ONLINE] ?: false
    }

    suspend fun setCountOptionalExtras(count: Boolean) {
        dataStore.edit { it[FoodKeys.COUNT_OPTIONAL_EXTRAS] = count }
    }

    suspend fun setLookUpBarcodesOnline(lookUp: Boolean) {
        dataStore.edit { it[FoodKeys.LOOK_UP_BARCODES_ONLINE] = lookUp }
    }

    /** Whether starting a recipe timer also starts one on the phone's clock app (docs/PLAN.md §11.11). Default true. */
    val phoneTimers: Flow<Boolean> = dataStore.data.map { prefs -> prefs[FoodKeys.PHONE_TIMERS] ?: true }

    suspend fun setPhoneTimers(on: Boolean) {
        dataStore.edit { it[FoodKeys.PHONE_TIMERS] = on }
    }

    /**
     * What is ticked off in the cooking view of [recipeId]: ingredient lines and steps, as the
     * screen names them (any strings). Empty until something is ticked.
     */
    fun observeCookingTicks(recipeId: Long): Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[FoodKeys.cookingTicks(recipeId)] ?: emptySet()
    }

    /** Replaces the ticks of [recipeId]; an empty set clears them. */
    suspend fun setCookingTicks(recipeId: Long, ticks: Set<String>) {
        dataStore.edit { prefs ->
            val key = FoodKeys.cookingTicks(recipeId)
            prefs.remove(key)
            if (ticks.isNotEmpty()) prefs[key] = ticks
        }
    }

    /** Ticks or unticks one item of [recipeId]'s cooking view, atomically. */
    suspend fun setCookingTick(recipeId: Long, tick: String, ticked: Boolean) {
        dataStore.edit { prefs ->
            val key = FoodKeys.cookingTicks(recipeId)
            val current: Set<String> = prefs[key] ?: emptySet()
            val next: Set<String> = if (ticked) current + tick else current - tick
            prefs.remove(key)
            if (next.isNotEmpty()) prefs[key] = next
        }
    }

    /** *Done cooking*: forgets the ticks of [recipeId]. */
    suspend fun clearCookingTicks(recipeId: Long) {
        dataStore.edit { it.remove(FoodKeys.cookingTicks(recipeId)) }
    }

    /** Forgets every recipe's cooking ticks (after a restore, when recipe ids mean other recipes). */
    @Suppress("UNCHECKED_CAST")
    suspend fun clearAllCookingTicks() {
        dataStore.edit { prefs ->
            val keys: List<Preferences.Key<*>> =
                prefs.asMap().keys.filter { it.name.startsWith(FoodKeys.COOKING_TICKS_PREFIX) }
            for (key in keys) prefs.remove(key as Preferences.Key<Any>)
        }
    }

    // ---- Planner (docs/PLAN.md §13.6) -------------------------------------------------------------

    /** When tasks without a time remind, and the end-of-day review. In backups ([plannerBackupMap]). */
    val plannerReminders: Flow<PlannerReminderSettings> = dataStore.data.map { prefs ->
        PlannerSettingsCodec.decodeReminders(prefs[PlannerKeys.REMINDERS])
    }

    suspend fun setPlannerReminders(settings: PlannerReminderSettings) {
        dataStore.edit { it[PlannerKeys.REMINDERS] = PlannerSettingsCodec.encodeReminders(settings) }
    }

    /** Changes the reminder settings in one step, so quick changes in a row don't undo each other. */
    suspend fun updatePlannerReminders(change: (PlannerReminderSettings) -> PlannerReminderSettings) {
        dataStore.edit { prefs ->
            prefs[PlannerKeys.REMINDERS] = PlannerSettingsCodec.encodeReminders(change(PlannerSettingsCodec.decodeReminders(prefs[PlannerKeys.REMINDERS])))
        }
    }

    /** The breakdown templates: the user's own, or the defaults. In backups. */
    val breakdownTemplates: Flow<List<BreakdownTemplate>> = dataStore.data.map { prefs ->
        PlannerSettingsCodec.decodeTemplates(prefs[PlannerKeys.TEMPLATES])
    }

    suspend fun setBreakdownTemplates(templates: List<BreakdownTemplate>) {
        dataStore.edit { it[PlannerKeys.TEMPLATES] = PlannerSettingsCodec.encodeTemplates(templates) }
    }

    /** *Reset to the defaults*: forgets the user's templates. */
    suspend fun resetBreakdownTemplates() {
        dataStore.edit { it.remove(PlannerKeys.TEMPLATES) }
    }

    /** The last time the Planner's alarm posted reminders (device bookkeeping: not in backups). */
    val plannerAlarmFired: Flow<Instant?> = dataStore.data.map { prefs ->
        prefs[PlannerKeys.ALARM_FIRED]?.takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
    }

    suspend fun setPlannerAlarmFired(at: Instant?) {
        dataStore.edit { it[PlannerKeys.ALARM_FIRED] = at?.toEpochMilli() ?: -1L }
    }

    /** The phone calendars the Planner shows and writes to (device-specific: not in backups). */
    val plannerCalendars: Flow<PlannerCalendarSettings> = dataStore.data.map { prefs ->
        PlannerSettingsCodec.decodeCalendars(prefs[PlannerKeys.CALENDARS])
    }.distinctUntilChanged()

    suspend fun setPlannerCalendars(settings: PlannerCalendarSettings) {
        dataStore.edit { it[PlannerKeys.CALENDARS] = PlannerSettingsCodec.encodeCalendars(settings) }
    }

    /** The user's day: where the record's days start and end, and wake and sleep times. In backups. */
    val plannerDay: Flow<DaySettings> = dataStore.data.map { prefs -> PlannerSettingsCodec.decodeDay(prefs[PlannerKeys.DAY]) }
        .distinctUntilChanged()

    suspend fun setPlannerDay(settings: DaySettings) {
        dataStore.edit { it[PlannerKeys.DAY] = PlannerSettingsCodec.encodeDay(settings) }
    }

    /** Meals, travel and the shortest useful gap, for free time. In backups. */
    val plannerFree: Flow<FreeSettings> = dataStore.data.map { prefs -> PlannerSettingsCodec.decodeFree(prefs[PlannerKeys.FREE]) }
        .distinctUntilChanged()

    suspend fun setPlannerFree(settings: FreeSettings) {
        dataStore.edit { it[PlannerKeys.FREE] = PlannerSettingsCodec.encodeFree(settings) }
    }

    /** Changes the free-time settings in one step, so quick changes in a row don't undo each other. */
    suspend fun updatePlannerFree(change: (FreeSettings) -> FreeSettings) {
        dataStore.edit { it[PlannerKeys.FREE] = PlannerSettingsCodec.encodeFree(change(PlannerSettingsCodec.decodeFree(it[PlannerKeys.FREE]))) }
    }

    /** The running timer, or null (device-specific: not in backups). */
    val plannerTimer: Flow<TimerState?> = dataStore.data.map { prefs -> PlannerSettingsCodec.decodeTimer(prefs[PlannerKeys.TIMER]) }
        .distinctUntilChanged()

    suspend fun setPlannerTimer(state: TimerState?) {
        dataStore.edit { prefs ->
            if (state == null) prefs.remove(PlannerKeys.TIMER) else prefs[PlannerKeys.TIMER] = PlannerSettingsCodec.encodeTimer(state)
        }
    }

    /** How study is suggested (docs/PLAN.md §13.6). In backups. */
    val plannerStudy: Flow<StudyPrefs> = dataStore.data.map { prefs -> PlannerSettingsCodec.decodeStudy(prefs[PlannerKeys.STUDY]) }
        .distinctUntilChanged()

    suspend fun setPlannerStudy(prefs: StudyPrefs) {
        dataStore.edit { it[PlannerKeys.STUDY] = PlannerSettingsCodec.encodeStudy(prefs) }
    }

    /** Changes how study is suggested in one step, so quick changes in a row don't undo each other. */
    suspend fun updatePlannerStudy(change: (StudyPrefs) -> StudyPrefs) {
        dataStore.edit { it[PlannerKeys.STUDY] = PlannerSettingsCodec.encodeStudy(change(PlannerSettingsCodec.decodeStudy(it[PlannerKeys.STUDY]))) }
    }

    /** Dates switched off for suggestions; past ones are dropped when the set is next written. In backups. */
    val plannerDaysOff: Flow<Set<LocalDate>> = dataStore.data.map { prefs -> PlannerSettingsCodec.decodeDaysOff(prefs[PlannerKeys.DAYS_OFF]) }
        .distinctUntilChanged()

    suspend fun setPlannerDaysOff(days: Set<LocalDate>, today: LocalDate) {
        dataStore.edit { it[PlannerKeys.DAYS_OFF] = PlannerSettingsCodec.encodeDaysOff(days.filter { d -> !d.isBefore(today) }.toSet()) }
    }

    /** Switches [date] off for suggestions, or back on, in one step; past days are dropped. */
    suspend fun setPlannerDayOff(date: LocalDate, off: Boolean, today: LocalDate) {
        dataStore.edit { prefs ->
            val days = PlannerSettingsCodec.decodeDaysOff(prefs[PlannerKeys.DAYS_OFF]).let { if (off) it + date else it - date }
            prefs[PlannerKeys.DAYS_OFF] = PlannerSettingsCodec.encodeDaysOff(days.filter { d -> !d.isBefore(today) }.toSet())
        }
    }

    /** The Planner preferences a backup carries, as entries of its `appSettings` map. */
    suspend fun plannerBackupMap(): Map<String, String> {
        val prefs = dataStore.data.first()
        return buildMap {
            prefs[PlannerKeys.REMINDERS]?.let { put(PLANNER_REMINDERS_BACKUP_KEY, it) }
            prefs[PlannerKeys.TEMPLATES]?.let { put(PLANNER_TEMPLATES_BACKUP_KEY, it) }
            prefs[PlannerKeys.DAY]?.let { put(PLANNER_DAY_BACKUP_KEY, it) }
            prefs[PlannerKeys.FREE]?.let { put(PLANNER_FREE_BACKUP_KEY, it) }
            prefs[PlannerKeys.STUDY]?.let { put(PLANNER_STUDY_BACKUP_KEY, it) }
            prefs[PlannerKeys.DAYS_OFF]?.let { put(PLANNER_DAYS_OFF_BACKUP_KEY, it) }
        }
    }

    /**
     * Restores the Planner preferences from a backup's `appSettings` map: present values replace the current ones
     * (a value that doesn't parse reads as the defaults later), and missing ones are left as they are.
     */
    suspend fun restorePlannerBackupMap(map: Map<String, String>) {
        dataStore.edit { prefs ->
            map[PLANNER_REMINDERS_BACKUP_KEY]?.let { prefs[PlannerKeys.REMINDERS] = it }
            map[PLANNER_TEMPLATES_BACKUP_KEY]?.let { prefs[PlannerKeys.TEMPLATES] = it }
            map[PLANNER_DAY_BACKUP_KEY]?.let { prefs[PlannerKeys.DAY] = it }
            map[PLANNER_FREE_BACKUP_KEY]?.let { prefs[PlannerKeys.FREE] = it }
            map[PLANNER_STUDY_BACKUP_KEY]?.let { prefs[PlannerKeys.STUDY] = it }
            map[PLANNER_DAYS_OFF_BACKUP_KEY]?.let { prefs[PlannerKeys.DAYS_OFF] = it }
        }
    }

    private object PlannerKeys {
        val REMINDERS = stringPreferencesKey("planner_reminders")
        val TEMPLATES = stringPreferencesKey("planner_templates")
        val ALARM_FIRED = longPreferencesKey("planner_alarm_fired")
        val CALENDARS = stringPreferencesKey("planner_calendars")
        val DAY = stringPreferencesKey("planner_day")
        val FREE = stringPreferencesKey("planner_free")
        val STUDY = stringPreferencesKey("planner_study")
        val DAYS_OFF = stringPreferencesKey("planner_days_off")
        val TIMER = stringPreferencesKey("planner_timer")
    }

    private object FoodKeys {
        const val COOKING_TICKS_PREFIX = "cooking_ticks_"
        val COUNT_OPTIONAL_EXTRAS = booleanPreferencesKey("count_optional_extras")
        val LOOK_UP_BARCODES_ONLINE = booleanPreferencesKey("look_up_barcodes_online")
        val PHONE_TIMERS = booleanPreferencesKey("phone_timers")

        fun cookingTicks(recipeId: Long) = stringSetPreferencesKey("$COOKING_TICKS_PREFIX$recipeId")
    }
}
