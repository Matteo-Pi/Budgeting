package io.github.matteopi.budgeting.domain

import io.github.matteopi.budgeting.core.forecast.ForecastEngine
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Combines every repository's data with settings and today's date into a [ForecastInput], and
 * runs [ForecastEngine] on a background dispatcher. See docs/PLAN.md §6.4.
 */
class ForecastService(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val planRepository: PlanRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
    appScope: CoroutineScope,
) {
    private data class CoreData(
        val accounts: List<Account>,
        val categories: List<Category>,
        val transactions: List<Transaction>,
    )

    private data class PlanData(
        val recurringRules: List<RecurringRule>,
        val scheduledItems: List<ScheduledItem>,
        val termPeriods: List<TermPeriod>,
        val predictions: List<Prediction>,
        val sinkingFunds: List<SinkingFund>,
    )

    private data class MiscData(
        val reconciliations: List<Reconciliation>,
        val settings: BudgetSettings,
        val today: LocalDate,
    )

    /** combine() is only typed up to 5 flows, so this is built in stages. */
    val inputFlow: Flow<ForecastInput> = run {
        val coreFlow = combine(
            accountRepository.observeAccounts(),
            categoryRepository.observeCategories(),
            transactionRepository.observeAll(),
        ) { accounts, categories, transactions -> CoreData(accounts, categories, transactions) }

        val planFlow = combine(
            planRepository.observeRecurringRules(),
            planRepository.observeScheduledItems(),
            planRepository.observeTermPeriods(),
            planRepository.observePredictions(),
            planRepository.observeSinkingFunds(),
        ) { rules, items, terms, predictions, funds -> PlanData(rules, items, terms, predictions, funds) }

        val miscFlow = combine(
            accountRepository.observeReconciliations(),
            settingsStore.budgetSettings,
            clock.todayFlow,
        ) { reconciliations, settings, today -> MiscData(reconciliations, settings, today) }

        combine(coreFlow, planFlow, miscFlow) { core, plan, misc ->
            ForecastInput(
                today = misc.today,
                accounts = core.accounts,
                categories = core.categories,
                transactions = core.transactions,
                recurringRules = plan.recurringRules,
                scheduledItems = plan.scheduledItems,
                termPeriods = plan.termPeriods,
                predictions = plan.predictions,
                sinkingFunds = plan.sinkingFunds,
                reconciliations = misc.reconciliations,
                settings = misc.settings,
            )
        }
    }

    /** Debounced, latest-wins forecast for the UI to observe. Null until the first input arrives. */
    val forecast: StateFlow<ForecastResult?> = inputFlow
        .debounce(200)
        .mapLatest { input -> withContext(Dispatchers.Default) { ForecastEngine.run(input) } }
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Current input, Monte Carlo skipped — fast path for the widget and the daily worker. */
    suspend fun quick(): ForecastResult {
        val input = inputFlow.first().copy(runMonteCarlo = false)
        return withContext(Dispatchers.Default) { ForecastEngine.run(input) }
    }

    /** Current input, full model (including Monte Carlo). */
    suspend fun full(): ForecastResult {
        val input = inputFlow.first()
        return withContext(Dispatchers.Default) { ForecastEngine.run(input) }
    }

    /** Current input with a what-if [scenario] overlaid. */
    suspend fun withScenario(scenario: Scenario): ForecastResult {
        val input = inputFlow.first().copy(scenario = scenario)
        return withContext(Dispatchers.Default) { ForecastEngine.run(input) }
    }
}
