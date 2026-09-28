package io.github.matteopi.budgeting.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.EstimateSource
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.SystemKeys
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.domain.ForecastService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How the person can control a category+regime's estimate (docs/PLAN.md §6.5). */
enum class PredictionEditMode { AUTOMATIC, MY_ESTIMATE, ALWAYS_USE, ADJUST_PERCENT }

/** One category+regime row on [PredictionsScreen]. */
data class PredictionRow(
    val category: Category,
    val regime: Regime,
    val automaticWeekly: Money,
    val finalWeekly: Money,
    val source: EstimateSource,
    val weeksOfData: Int,
    val needsInput: Boolean,
    val suggestedWeekly: Money?,
    val prediction: Prediction?,
    val readOnly: Boolean,
) {
    val editMode: PredictionEditMode
        get() = when (prediction?.mode) {
            PredictionMode.PRIOR -> PredictionEditMode.MY_ESTIMATE
            PredictionMode.REPLACE -> PredictionEditMode.ALWAYS_USE
            PredictionMode.ADJUST_PERCENT -> PredictionEditMode.ADJUST_PERCENT
            null -> PredictionEditMode.AUTOMATIC
        }
}

data class PredictionsUiState(
    val rows: List<PredictionRow> = emptyList(),
    val loading: Boolean = true,
)

class PredictionsViewModel(
    private val planRepository: PlanRepository,
    categoryRepository: CategoryRepository,
    forecastService: ForecastService,
) : ViewModel() {
    val uiState: StateFlow<PredictionsUiState> = combine(
        categoryRepository.observeCategories(),
        planRepository.observePredictions(),
        forecastService.forecast,
    ) { categories, predictions, forecast ->
        val learned = categories.filter { it.forecastMode == ForecastMode.LEARNED && !it.archived }
        val needsByKey = forecast?.needsPrediction?.associateBy { it.categoryId to it.regime } ?: emptyMap()
        val estimateByKey = forecast?.categoryEstimates?.associateBy { it.categoryId to it.regime } ?: emptyMap()
        val predictionByKey = predictions.associateBy { it.categoryId to it.regime }
        val rows = learned.flatMap { category ->
            listOf(Regime.TERM, Regime.HOLIDAY).map { regime ->
                val key = category.id to regime
                val estimate = estimateByKey[key]
                val needs = needsByKey[key]
                PredictionRow(
                    category = category,
                    regime = regime,
                    automaticWeekly = estimate?.automaticWeeklyAmount ?: Money.ZERO,
                    finalWeekly = estimate?.weeklyAmount ?: Money.ZERO,
                    source = estimate?.source ?: EstimateSource.NONE,
                    weeksOfData = estimate?.weeksOfData ?: 0,
                    needsInput = needs != null,
                    suggestedWeekly = needs?.suggestedWeekly ?: estimate?.automaticWeeklyAmount?.abs(),
                    prediction = predictionByKey[key],
                    readOnly = category.systemKey == SystemKeys.UNTRACKED,
                )
            }
        }.sortedWith(compareBy({ it.category.sortOrder }, { it.regime }))
        PredictionsUiState(rows = rows, loading = forecast == null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PredictionsUiState())

    fun setAutomatic(row: PredictionRow) {
        viewModelScope.launch { planRepository.deletePrediction(row.category.id, row.regime) }
    }

    fun setNoSpend(row: PredictionRow) {
        viewModelScope.launch {
            planRepository.upsertPrediction(
                Prediction(categoryId = row.category.id, regime = row.regime, mode = PredictionMode.PRIOR, weeklyAmount = Money.ZERO),
            )
        }
    }

    /** Returns an error message, or null on success. */
    fun save(row: PredictionRow, mode: PredictionEditMode, amountText: String, percentText: String): String? {
        when (mode) {
            PredictionEditMode.AUTOMATIC -> setAutomatic(row)
            PredictionEditMode.MY_ESTIMATE -> {
                val amount = Money.parse(amountText) ?: return "Enter a weekly amount."
                upsert(row, PredictionMode.PRIOR, weeklyAmount = amount.abs())
            }
            PredictionEditMode.ALWAYS_USE -> {
                val amount = Money.parse(amountText) ?: return "Enter a weekly amount."
                upsert(row, PredictionMode.REPLACE, weeklyAmount = amount.abs())
            }
            PredictionEditMode.ADJUST_PERCENT -> {
                val percent = percentText.trim().toIntOrNull() ?: return "Enter a whole number percentage."
                upsert(row, PredictionMode.ADJUST_PERCENT, adjustPercent = percent)
            }
        }
        return null
    }

    private fun upsert(row: PredictionRow, mode: PredictionMode, weeklyAmount: Money = Money.ZERO, adjustPercent: Int = 0) {
        viewModelScope.launch {
            planRepository.upsertPrediction(
                Prediction(
                    categoryId = row.category.id,
                    regime = row.regime,
                    mode = mode,
                    weeklyAmount = weeklyAmount,
                    adjustPercent = adjustPercent,
                ),
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                PredictionsViewModel(
                    planRepository = app.container.planRepository,
                    categoryRepository = app.container.categoryRepository,
                    forecastService = app.container.forecastService,
                )
            }
        }
    }
}

/** Plain-English label for [EstimateSource] (docs/PLAN.md §6.5: "Data / Blended / Fallback / Your estimate / None"). */
fun EstimateSource.shortLabel(): String = when (this) {
    EstimateSource.DATA -> "Data"
    EstimateSource.BLENDED -> "Blended"
    EstimateSource.FALLBACK_OVERALL -> "Fallback"
    EstimateSource.PREDICTION_PRIOR, EstimateSource.PREDICTION_REPLACE, EstimateSource.PREDICTION_ADJUSTED -> "Your estimate"
    EstimateSource.NONE -> "None"
}
