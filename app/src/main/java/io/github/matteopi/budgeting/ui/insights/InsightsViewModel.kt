package io.github.matteopi.budgeting.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.analysis.CategoryRegimeAverage
import io.github.matteopi.budgeting.core.analysis.MonthInOut
import io.github.matteopi.budgeting.core.analysis.Trends
import io.github.matteopi.budgeting.core.analysis.WeekTotals
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The period selector on the Insights screen (docs/PLAN.md §6.5 "Insights"). */
enum class InsightsPeriod(val label: String) {
    WEEKS_12("12 weeks"),
    MONTHS_6("6 months"),
    MONTHS_12("12 months"),
}

data class InsightsUiState(
    val loading: Boolean = true,
    val period: InsightsPeriod = InsightsPeriod.WEEKS_12,
    val categories: List<Category> = emptyList(),
    val weeklyByCategory: List<WeekTotals> = emptyList(),
    val thisMonthVsAverage: List<CategoryMonthComparison> = emptyList(),
    val termVsHoliday: List<CategoryRegimeAverage> = emptyList(),
    val balanceHistory: List<Pair<LocalDate, Money>> = emptyList(),
    val monthlyInOut: List<MonthInOut> = emptyList(),
    val selectedCategoryId: Long? = null,
)

class InsightsViewModel(private val container: AppContainer) : ViewModel() {
    private val periodFlow = MutableStateFlow(InsightsPeriod.WEEKS_12)
    private val selectedCategoryFlow = MutableStateFlow<Long?>(null)

    private data class CoreData(
        val accounts: List<Account>,
        val categories: List<Category>,
        val transactions: List<Transaction>,
        val terms: List<TermPeriod>,
        val today: LocalDate,
    )

    val uiState: StateFlow<InsightsUiState> = run {
        val coreFlow = combine(
            container.accountRepository.observeAccounts(),
            container.categoryRepository.observeCategories(),
            container.transactionRepository.observeAll(),
            container.planRepository.observeTermPeriods(),
            container.clock.todayFlow,
        ) { accounts, categories, transactions, terms, today ->
            CoreData(accounts, categories, transactions, terms, today)
        }

        combine(coreFlow, container.settingsStore.budgetSettings, periodFlow, selectedCategoryFlow) { core, settings, period, selectedCategory ->
            buildState(core, settings.historyStartDate, period, selectedCategory)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun selectPeriod(period: InsightsPeriod) {
        periodFlow.value = period
    }

    fun selectCategory(categoryId: Long?) {
        selectedCategoryFlow.value = if (selectedCategoryFlow.value == categoryId) null else categoryId
    }

    private fun buildState(core: CoreData, historyStart: LocalDate?, period: InsightsPeriod, selectedCategoryId: Long?): InsightsUiState {
        val to = core.today
        val from = when (period) {
            InsightsPeriod.WEEKS_12 -> to.minusWeeks(12)
            InsightsPeriod.MONTHS_6 -> to.minusMonths(6)
            InsightsPeriod.MONTHS_12 -> to.minusMonths(12)
        }
        val months = when (period) {
            InsightsPeriod.WEEKS_12 -> 3
            InsightsPeriod.MONTHS_6 -> 6
            InsightsPeriod.MONTHS_12 -> 12
        }

        val weekly = Trends.weeklyByCategory(core.accounts, core.categories, core.transactions, from, to)
        val termVsHoliday = Trends.termVsHoliday(core.accounts, core.categories, core.transactions, core.terms, historyStart)
        val balanceHistory = Trends.balanceHistory(core.accounts, core.transactions, from, to)
        val monthlyInOut = Trends.monthlyInOut(core.accounts, core.categories, core.transactions, months, to)

        val learnedCategoryIds = core.categories.filter { it.forecastMode == ForecastMode.LEARNED }.map { it.id }
        val monthComparison = InsightsMapping.thisMonthVsAverage(weekly, learnedCategoryIds, to)

        return InsightsUiState(
            loading = false,
            period = period,
            categories = core.categories,
            weeklyByCategory = weekly,
            thisMonthVsAverage = monthComparison,
            termVsHoliday = termVsHoliday,
            balanceHistory = balanceHistory,
            monthlyInOut = monthlyInOut,
            selectedCategoryId = selectedCategoryId,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                InsightsViewModel(app.container)
            }
        }
    }
}
