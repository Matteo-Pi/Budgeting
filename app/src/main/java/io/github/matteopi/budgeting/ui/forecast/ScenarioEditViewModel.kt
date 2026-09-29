package io.github.matteopi.budgeting.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.ui.components.charts.ForecastChartDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The comparison table: baseline forecast vs. the forecast with the scenario's items applied. */
data class ScenarioComparison(
    val baselineWeekly: Money,
    val scenarioWeekly: Money,
    val baselineEndBalance: Money,
    val scenarioEndBalance: Money,
    val baselineProbBelowFloor: Double,
    val scenarioProbBelowFloor: Double,
)

data class ScenarioEditUiState(
    val loading: Boolean = true,
    val name: String = "",
    val items: List<ScenarioItem> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val comparison: ScenarioComparison? = null,
    val baselineChart: List<ForecastChartDay> = emptyList(),
    val scenarioChart: List<ForecastChartDay> = emptyList(),
    val computing: Boolean = false,
)

class ScenarioEditViewModel(private val container: AppContainer, private val scenarioId: Long?) : ViewModel() {
    private val nameFlow = MutableStateFlow("New scenario")
    private val itemsFlow = MutableStateFlow<List<ScenarioItem>>(emptyList())
    private val comparisonFlow = MutableStateFlow<ScenarioComparison?>(null)
    private val chartsFlow = MutableStateFlow(emptyList<ForecastChartDay>() to emptyList<ForecastChartDay>())
    private val computingFlow = MutableStateFlow(false)

    private data class NameItems(val name: String, val items: List<ScenarioItem>)
    private data class Refs(val accounts: List<Account>, val categories: List<Category>, val zeroPoint: ZeroPoint)
    private data class CompCharts(
        val comparison: ScenarioComparison?,
        val charts: Pair<List<ForecastChartDay>, List<ForecastChartDay>>,
        val computing: Boolean,
    )

    val uiState: StateFlow<ScenarioEditUiState> = run {
        val nameItems = combine(nameFlow, itemsFlow) { name, items -> NameItems(name, items) }
        val refs = combine(
            container.accountRepository.observeAccounts(),
            container.categoryRepository.observeCategories(),
            container.settingsStore.budgetSettings,
        ) { accounts, categories, settings -> Refs(accounts, categories, settings.zeroPoint) }
        val compCharts = combine(comparisonFlow, chartsFlow, computingFlow) { comparison, charts, computing ->
            CompCharts(comparison, charts, computing)
        }
        combine(nameItems, refs, compCharts) { ni, r, cc ->
            ScenarioEditUiState(
                loading = false,
                name = ni.name,
                items = ni.items,
                accounts = r.accounts,
                categories = r.categories,
                comparison = cc.comparison,
                baselineChart = cc.charts.first,
                scenarioChart = cc.charts.second,
                computing = cc.computing,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScenarioEditUiState())

    init {
        viewModelScope.launch {
            if (scenarioId != null) {
                val existing = container.scenarioRepository.observeScenarios().first()
                    .firstOrNull { it.id == scenarioId }
                if (existing != null) {
                    nameFlow.value = existing.name
                    itemsFlow.value = existing.items
                }
            }
        }
        viewModelScope.launch {
            combine(nameFlow, itemsFlow) { name, items -> Scenario(id = scenarioId ?: 0, name = name, items = items) }
                .debounce(400)
                .collectLatest { draft ->
                    computingFlow.value = true
                    // Baseline and scenario are worked out from ONE input, and so is the zero point.
                    val run = container.forecastService.runScenario(draft)
                    val zeroPoint = run.input.settings.zeroPoint
                    comparisonFlow.value = buildComparison(run.baseline, run.withScenario, zeroPoint)
                    chartsFlow.value = ForecastChartMapping.toChartDays(run.baseline, zeroPoint) to
                        ForecastChartMapping.toChartDays(run.withScenario, zeroPoint)
                    computingFlow.value = false
                }
        }
    }

    fun setName(name: String) {
        nameFlow.value = name
    }

    fun addItem(item: ScenarioItem) {
        itemsFlow.value = itemsFlow.value + item
    }

    fun updateItem(index: Int, item: ScenarioItem) {
        itemsFlow.value = itemsFlow.value.toMutableList().also { if (index in it.indices) it[index] = item }
    }

    fun removeItem(index: Int) {
        itemsFlow.value = itemsFlow.value.toMutableList().also { if (index in it.indices) it.removeAt(index) }
    }

    private val closedFlow = MutableStateFlow(false)

    /** True once the draft has been saved and the editor should close. */
    val closed: StateFlow<Boolean> = closedFlow

    /**
     * Saves the draft, then signals [closed]. The screen navigates from composition when it sees
     * that, rather than from the coroutine that did the database write.
     */
    fun saveAndClose() {
        viewModelScope.launch {
            save()
            closedFlow.value = true
        }
    }

    /** Saves the current draft (create or update) and returns its id. */
    suspend fun save(): Long {
        val scenario = Scenario(id = scenarioId ?: 0, name = nameFlow.value, items = itemsFlow.value)
        return if (scenarioId == null) {
            container.scenarioRepository.addScenario(scenario)
        } else {
            container.scenarioRepository.updateScenario(scenario)
            scenarioId
        }
    }

    /**
     * Converts the draft's items into real plan entries: ONE_OFF -> a scheduled item, RECURRING
     * -> a recurring rule, CATEGORY_ADJUST -> an ADJUST_PERCENT prediction (docs/PLAN.md §6.5
     * "What-if"). The caller confirms with the user before calling this.
     */
    suspend fun applyToPlan() {
        val defaultAccountId = container.accountRepository.defaultAccountId() ?: return

        for (item in itemsFlow.value) {
            when (item.type) {
                ScenarioItemType.ONE_OFF -> {
                    val date = item.date ?: continue
                    container.planRepository.addScheduledItem(
                        ScheduledItem(
                            label = item.label,
                            accountId = item.accountId ?: defaultAccountId,
                            categoryId = item.categoryId,
                            amount = item.amount,
                            date = date,
                            postingMode = if (item.amount.isPositive) PostingMode.CONFIRM else PostingMode.AUTO,
                        ),
                    )
                }

                ScenarioItemType.RECURRING -> {
                    val start = item.date ?: continue
                    val frequency = item.frequency ?: continue
                    container.planRepository.addRecurringRule(
                        RecurringRule(
                            label = item.label,
                            accountId = item.accountId ?: defaultAccountId,
                            categoryId = item.categoryId,
                            amount = item.amount,
                            frequency = frequency,
                            interval = item.interval,
                            startDate = start,
                            endDate = item.endDate,
                            postingMode = if (item.amount.isPositive) PostingMode.CONFIRM else PostingMode.AUTO,
                        ),
                    )
                }

                ScenarioItemType.CATEGORY_ADJUST -> {
                    val categoryId = item.categoryId ?: continue
                    val regimes = item.regime?.let { listOf(it) } ?: Regime.entries
                    for (regime in regimes) {
                        container.planRepository.upsertPrediction(
                            Prediction(
                                categoryId = categoryId,
                                regime = regime,
                                mode = PredictionMode.ADJUST_PERCENT,
                                adjustPercent = item.percent,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun buildComparison(baseline: ForecastResult, scenario: ForecastResult, zeroPoint: ZeroPoint): ScenarioComparison {
        val horizonEnd = baseline.safeToSpend.horizonEnd
        val baselineEndRaw = baseline.days.firstOrNull { it.date == horizonEnd }
            ?.let { it.p50 ?: it.expectedBalance } ?: Money.ZERO
        val scenarioEndRaw = scenario.days.firstOrNull { it.date == horizonEnd }
            ?.let { it.p50 ?: it.expectedBalance } ?: Money.ZERO
        return ScenarioComparison(
            baselineWeekly = baseline.safeToSpend.weekly,
            scenarioWeekly = scenario.safeToSpend.weekly,
            baselineEndBalance = Balances.toDisplay(baselineEndRaw, baseline.poolOverdraft, zeroPoint),
            scenarioEndBalance = Balances.toDisplay(scenarioEndRaw, scenario.poolOverdraft, zeroPoint),
            baselineProbBelowFloor = baseline.risk?.probBelowFloor ?: 0.0,
            scenarioProbBelowFloor = scenario.risk?.probBelowFloor ?: 0.0,
        )
    }

    companion object {
        fun factory(scenarioId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                ScenarioEditViewModel(app.container, scenarioId)
            }
        }
    }
}
