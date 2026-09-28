package io.github.matteopi.budgeting.domain

import android.util.Log
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The baseline forecast and the forecast with a what-if scenario overlaid, both worked out from one [input]. */
data class ScenarioRun(
    val input: ForecastInput,
    val baseline: ForecastResult,
    val withScenario: ForecastResult,
)

/**
 * Combines every repository's data with settings and today's date into ONE [ForecastInput], and
 * runs [ForecastEngine] on a background dispatcher. See docs/PLAN.md §6.4.
 *
 * The UI observes [state], whose [ForecastState.Ready] carries the input together with the result
 * computed from it. Screens must not combine that with their own subscriptions to the same
 * repositories: two subscriptions emit at different moments, which is how Home once showed
 * account rows next to a forecast worked out for an older day (£0.00 "Your money").
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

    /** combine() is only typed up to 5 flows, so this is built in stages. Cold: every collector re-reads the data. */
    private val freshInput: Flow<ForecastInput> = run {
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

    /**
     * The input, built once and shared, only when it actually changed. The replay cache is dropped
     * as soon as nobody is listening (`replayExpirationMillis = 0`) so a returning subscriber
     * never starts from an input that went stale while the app was in the background.
     */
    private val sharedInput: SharedFlow<ForecastInput> = freshInput
        .distinctUntilChanged()
        .shareIn(appScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)

    /** The last successful calculation, so a failure can still offer numbers. */
    @Volatile
    private var lastGood: ForecastState.Ready? = null

    /**
     * Debounced, latest-wins forecast for the UI to observe: [ForecastState.Loading] until the
     * first calculation, then [ForecastState.Ready] (input + the result computed from it) or
     * [ForecastState.Failed].
     */
    val state: StateFlow<ForecastState> = sharedInput
        .debounce(200)
        .mapLatest { input -> compute(input) }
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), ForecastState.Loading)

    private suspend fun compute(input: ForecastInput): ForecastState {
        val outcome = withContext(Dispatchers.Default) {
            ForecastStates.compute(
                input = input,
                lastGood = lastGood,
                nowMillis = clock::nowMillis,
                run = { forecastInput -> ForecastEngine.run(forecastInput) },
                onError = { error -> Log.e(TAG, "Forecast failed", error) },
            )
        }
        // After withContext, so a computation that was cancelled meanwhile never becomes "last good".
        if (outcome is ForecastState.Ready) lastGood = outcome
        return outcome
    }

    /** The input as of right now (a fresh read, never a cached one). */
    suspend fun currentInput(): ForecastInput = freshInput.first()

    /** Current input, Monte Carlo skipped — fast path for the widget and the daily worker. */
    suspend fun quick(): ForecastResult {
        val input = currentInput().copy(runMonteCarlo = false)
        return withContext(Dispatchers.Default) { ForecastEngine.run(input) }
    }

    /** Current input, full model (including Monte Carlo). */
    suspend fun full(): ForecastResult {
        val input = currentInput()
        return withContext(Dispatchers.Default) { ForecastEngine.run(input) }
    }

    /** Current input with a what-if [scenario] overlaid. */
    suspend fun withScenario(scenario: Scenario): ForecastResult {
        val input = currentInput().copy(scenario = scenario)
        return withContext(Dispatchers.Default) { ForecastEngine.run(input) }
    }

    /** Baseline and scenario forecasts from the *same* input, for the what-if comparison. */
    suspend fun runScenario(scenario: Scenario): ScenarioRun {
        val input = currentInput()
        return withContext(Dispatchers.Default) {
            ScenarioRun(
                input = input,
                baseline = ForecastEngine.run(input),
                withScenario = ForecastEngine.run(input.copy(scenario = scenario)),
            )
        }
    }

    private companion object {
        const val TAG = "ForecastService"
    }
}
