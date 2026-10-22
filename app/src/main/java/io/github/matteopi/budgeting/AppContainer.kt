package io.github.matteopi.budgeting

import android.content.Context
import androidx.room.Room
import io.github.matteopi.budgeting.data.calendar.CalendarAccess
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.BackupRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.ImportRepository
import io.github.matteopi.budgeting.data.repo.IngredientRepository
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.PresetRepository
import io.github.matteopi.budgeting.data.repo.ReceiptRepository
import io.github.matteopi.budgeting.data.repo.RecipeRepository
import io.github.matteopi.budgeting.data.repo.ScenarioRepository
import io.github.matteopi.budgeting.data.repo.ShoppingRepository
import io.github.matteopi.budgeting.data.repo.SnapshotRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.HabitRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.SuggestionRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.ForecastService
import io.github.matteopi.budgeting.domain.PostingService
import io.github.matteopi.budgeting.domain.planner.PlannerCalendar
import io.github.matteopi.budgeting.domain.planner.PlannerCalendarSync
import io.github.matteopi.budgeting.domain.planner.HabitService
import io.github.matteopi.budgeting.domain.planner.InsightsService
import io.github.matteopi.budgeting.domain.planner.PlannerService
import io.github.matteopi.budgeting.domain.planner.PlannerTimer
import io.github.matteopi.budgeting.domain.planner.SuggestionService
import io.github.matteopi.budgeting.ui.food.timers.CookingTimers
import io.github.matteopi.budgeting.widget.WidgetUpdater
import io.github.matteopi.budgeting.widget.planner.PlannerWidget
import io.github.matteopi.budgeting.work.planner.CalendarSyncWorker
import io.github.matteopi.budgeting.work.planner.PlannerAlarms
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * Manual dependency-injection root, created once in [BudgetApp.onCreate] and reachable via
 * `(context.applicationContext as BudgetApp).container`. No Hilt/Koin (docs/PLAN.md §6.4).
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: BudgetDatabase = Room.databaseBuilder(
        context.applicationContext,
        BudgetDatabase::class.java,
        BudgetDatabase.DATABASE_NAME,
    ).build()

    val settingsStore = SettingsStore(context.applicationContext)
    val clock = Clock()

    /** The recipe timers (docs/PLAN.md §11.11), app-wide so they keep running between screens. */
    val cookingTimers = CookingTimers(appScope)
    val widgetUpdater = WidgetUpdater(context.applicationContext, appScope)

    /**
     * After Planner writes (docs/PLAN.md §13.5): the reminder alarm and the Planner widget follow, and with writing
     * back on, the calendar sync. [plannerAlarms] is built below.
     */
    val plannerUpdater: PlannerUpdater = PlannerUpdater(appScope) {
        plannerAlarms.reschedule()
        PlannerWidget.refresh(context.applicationContext)
        if (settingsStore.plannerCalendars.first().writeTo != null) CalendarSyncWorker.enqueue(context.applicationContext)
    }

    val accountRepository = AccountRepository(
        database = database,
        accountDao = database.accountDao(),
        transactionDao = database.transactionDao(),
        reconciliationDao = database.reconciliationDao(),
        categoryDao = database.categoryDao(),
        clock = clock,
        widgetUpdater = widgetUpdater,
        settingsStore = settingsStore,
    )

    val transactionRepository = TransactionRepository(
        database = database,
        transactionDao = database.transactionDao(),
        clock = clock,
        widgetUpdater = widgetUpdater,
    )

    val categoryRepository = CategoryRepository(
        categoryDao = database.categoryDao(),
        widgetUpdater = widgetUpdater,
    )

    val planRepository = PlanRepository(
        planDao = database.planDao(),
        widgetUpdater = widgetUpdater,
        clock = clock,
    )

    val scenarioRepository = ScenarioRepository(
        database = database,
        scenarioDao = database.scenarioDao(),
        clock = clock,
        widgetUpdater = widgetUpdater,
    )

    val presetRepository = PresetRepository(
        database = database,
        presetDao = database.presetDao(),
        widgetUpdater = widgetUpdater,
    )

    val importRepository = ImportRepository(
        database = database,
        importDao = database.importDao(),
        transactionDao = database.transactionDao(),
        widgetUpdater = widgetUpdater,
    )

    val receiptRepository = ReceiptRepository(
        database = database,
        receiptDao = database.receiptDao(),
        transactionDao = database.transactionDao(),
        widgetUpdater = widgetUpdater,
    )

    val ingredientRepository = IngredientRepository(
        foodDao = database.foodDao(),
        clock = clock,
    )

    val recipeRepository = RecipeRepository(
        database = database,
        foodDao = database.foodDao(),
        clock = clock,
    )

    val shoppingRepository = ShoppingRepository(
        foodDao = database.foodDao(),
        clock = clock,
    )

    val moduleRepository: ModuleRepository = ModuleRepository(
        plannerDao = database.plannerDao(),
        plannerUpdater = plannerUpdater,
    )

    val taskRepository: TaskRepository = TaskRepository(
        database = database,
        plannerDao = database.plannerDao(),
        clock = clock,
        plannerUpdater = plannerUpdater,
    )

    val timetableRepository: TimetableRepository = TimetableRepository(
        database = database,
        timetableDao = database.timetableDao(),
        clock = clock,
        plannerUpdater = plannerUpdater,
    )

    val dayRepository: DayRepository = DayRepository(
        dayDao = database.dayDao(),
        clock = clock,
        plannerUpdater = plannerUpdater,
    )

    val habitRepository: HabitRepository = HabitRepository(
        habitDao = database.habitDao(),
        plannerUpdater = plannerUpdater,
    )

    /** Habits' progress and streaks (docs/PLAN.md §13.9), for the Habits tab, Today and the reminders. */
    val habitService = HabitService(
        habitRepository = habitRepository,
        dayRepository = dayRepository,
        planRepository = planRepository,
        settingsStore = settingsStore,
        clock = clock,
    )

    val suggestionRepository: SuggestionRepository = SuggestionRepository(
        studyDao = database.studyDao(),
        dayRepository = dayRepository,
        clock = clock,
        plannerUpdater = plannerUpdater,
    )

    /** The phone's calendars (docs/PLAN.md §13.7): read offline, and written to only when the user turns it on. */
    val calendarAccess = CalendarAccess(context.applicationContext)

    val plannerCalendar = PlannerCalendar(
        access = calendarAccess,
        settingsStore = settingsStore,
        timetableRepository = timetableRepository,
        clock = clock,
    )

    val plannerCalendarSync = PlannerCalendarSync(
        access = calendarAccess,
        settingsStore = settingsStore,
        taskRepository = taskRepository,
        timetableRepository = timetableRepository,
        planRepository = planRepository,
        dayRepository = dayRepository,
        clock = clock,
    )

    /** The Planner's single reminder alarm (docs/PLAN.md §13.7). */
    val plannerAlarms: PlannerAlarms = PlannerAlarms(
        context = context.applicationContext,
        taskRepository = taskRepository,
        dayRepository = dayRepository,
        timetableRepository = timetableRepository,
        planRepository = planRepository,
        habitService = habitService,
        settingsStore = settingsStore,
        clock = clock,
    )

    /** The day record's shared computations (docs/PLAN.md §13.9), for Today, the day editor and the widget. */
    val plannerService = PlannerService(
        dayRepository = dayRepository,
        timetableRepository = timetableRepository,
        taskRepository = taskRepository,
        planRepository = planRepository,
        plannerCalendar = plannerCalendar,
        settingsStore = settingsStore,
        clock = clock,
    )

    /** Suggested study time (docs/PLAN.md §13.9), for Today and the timetable. */
    val suggestionService = SuggestionService(
        dayRepository = dayRepository,
        timetableRepository = timetableRepository,
        taskRepository = taskRepository,
        planRepository = planRepository,
        moduleRepository = moduleRepository,
        suggestionRepository = suggestionRepository,
        plannerCalendar = plannerCalendar,
        habitService = habitService,
        settingsStore = settingsStore,
        clock = clock,
    )

    /** The Planner's Insights tab (docs/PLAN.md §13.9). */
    val insightsService = InsightsService(
        dayRepository = dayRepository,
        timetableRepository = timetableRepository,
        taskRepository = taskRepository,
        planRepository = planRepository,
        moduleRepository = moduleRepository,
        habitService = habitService,
        settingsStore = settingsStore,
        clock = clock,
    )

    val plannerTimer = PlannerTimer(
        context = context.applicationContext,
        settingsStore = settingsStore,
        dayRepository = dayRepository,
        moduleRepository = moduleRepository,
        clock = clock,
    )

    val snapshotRepository = SnapshotRepository(
        database = database,
        snapshotDao = database.snapshotDao(),
        widgetUpdater = widgetUpdater,
    )

    val backupRepository = BackupRepository(
        database = database,
        accountDao = database.accountDao(),
        categoryDao = database.categoryDao(),
        transactionDao = database.transactionDao(),
        planDao = database.planDao(),
        scenarioDao = database.scenarioDao(),
        presetDao = database.presetDao(),
        importDao = database.importDao(),
        reconciliationDao = database.reconciliationDao(),
        snapshotDao = database.snapshotDao(),
        receiptDao = database.receiptDao(),
        foodDao = database.foodDao(),
        plannerDao = database.plannerDao(),
        timetableDao = database.timetableDao(),
        dayDao = database.dayDao(),
        habitDao = database.habitDao(),
        studyDao = database.studyDao(),
        settingsStore = settingsStore,
        widgetUpdater = widgetUpdater,
        plannerUpdater = plannerUpdater,
    )

    val forecastService = ForecastService(
        accountRepository = accountRepository,
        transactionRepository = transactionRepository,
        categoryRepository = categoryRepository,
        planRepository = planRepository,
        settingsStore = settingsStore,
        clock = clock,
        appScope = appScope,
    )

    val postingService = PostingService(
        database = database,
        planRepository = planRepository,
        transactionRepository = transactionRepository,
        clock = clock,
    )
}
