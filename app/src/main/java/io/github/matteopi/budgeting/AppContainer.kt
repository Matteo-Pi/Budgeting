package io.github.matteopi.budgeting

import android.content.Context
import androidx.room.Room
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.BackupRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.ImportRepository
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.PresetRepository
import io.github.matteopi.budgeting.data.repo.ScenarioRepository
import io.github.matteopi.budgeting.data.repo.SnapshotRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.ForecastService
import io.github.matteopi.budgeting.domain.PostingService
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val widgetUpdater = WidgetUpdater(context.applicationContext, appScope)

    val accountRepository = AccountRepository(
        database = database,
        accountDao = database.accountDao(),
        transactionDao = database.transactionDao(),
        reconciliationDao = database.reconciliationDao(),
        categoryDao = database.categoryDao(),
        clock = clock,
        widgetUpdater = widgetUpdater,
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
        settingsStore = settingsStore,
        widgetUpdater = widgetUpdater,
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
        planRepository = planRepository,
        transactionRepository = transactionRepository,
        clock = clock,
    )
}
