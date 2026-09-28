package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.forecast.EstimateSource
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Regime
import kotlin.test.Test
import kotlin.test.assertEquals

class PredictionsLogicTest {
    private val category = Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)

    private fun row(prediction: Prediction?) = PredictionRow(
        category = category,
        regime = Regime.TERM,
        automaticWeekly = Money.pounds(-30),
        finalWeekly = Money.pounds(-30),
        source = EstimateSource.DATA,
        weeksOfData = 8,
        needsInput = false,
        suggestedWeekly = Money.pounds(30),
        prediction = prediction,
        readOnly = false,
    )

    @Test
    fun shortLabelsMatchThePlan() {
        assertEquals("Data", EstimateSource.DATA.shortLabel())
        assertEquals("Blended", EstimateSource.BLENDED.shortLabel())
        assertEquals("Fallback", EstimateSource.FALLBACK_OVERALL.shortLabel())
        assertEquals("Your estimate", EstimateSource.PREDICTION_PRIOR.shortLabel())
        assertEquals("Your estimate", EstimateSource.PREDICTION_REPLACE.shortLabel())
        assertEquals("Your estimate", EstimateSource.PREDICTION_ADJUSTED.shortLabel())
        assertEquals("None", EstimateSource.NONE.shortLabel())
    }

    @Test
    fun editModeReflectsNoPredictionAsAutomatic() {
        assertEquals(PredictionEditMode.AUTOMATIC, row(prediction = null).editMode)
    }

    @Test
    fun editModeReflectsEachPredictionMode() {
        val prior = Prediction(categoryId = 1, regime = Regime.TERM, mode = PredictionMode.PRIOR, weeklyAmount = Money.pounds(20))
        assertEquals(PredictionEditMode.MY_ESTIMATE, row(prior).editMode)

        val replace = prior.copy(mode = PredictionMode.REPLACE)
        assertEquals(PredictionEditMode.ALWAYS_USE, row(replace).editMode)

        val adjust = prior.copy(mode = PredictionMode.ADJUST_PERCENT, adjustPercent = -20)
        assertEquals(PredictionEditMode.ADJUST_PERCENT, row(adjust).editMode)
    }
}
