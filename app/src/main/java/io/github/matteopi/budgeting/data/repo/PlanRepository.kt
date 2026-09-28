package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.data.db.dao.PlanDao
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Recurring rules, scheduled items, term periods, predictions and sinking funds. */
class PlanRepository(
    private val planDao: PlanDao,
    private val widgetUpdater: WidgetUpdater,
) {
    // Recurring rules.
    fun observeRecurringRules(): Flow<List<RecurringRule>> =
        planDao.observeRecurringRules().map { list -> list.map { it.toModel() } }

    suspend fun addRecurringRule(rule: RecurringRule): Long {
        val id = planDao.insertRecurringRule(rule.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    suspend fun updateRecurringRule(rule: RecurringRule) {
        planDao.updateRecurringRule(rule.toEntity())
        widgetUpdater.requestUpdate()
    }

    suspend fun deleteRecurringRule(id: Long) {
        planDao.deleteRecurringRule(id)
        widgetUpdater.requestUpdate()
    }

    // Scheduled items.
    fun observeScheduledItems(): Flow<List<ScheduledItem>> =
        planDao.observeScheduledItems().map { list -> list.map { it.toModel() } }

    suspend fun addScheduledItem(item: ScheduledItem): Long {
        val id = planDao.insertScheduledItem(item.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    suspend fun updateScheduledItem(item: ScheduledItem) {
        planDao.updateScheduledItem(item.toEntity())
        widgetUpdater.requestUpdate()
    }

    suspend fun deleteScheduledItem(id: Long) {
        planDao.deleteScheduledItem(id)
        widgetUpdater.requestUpdate()
    }

    // Term periods.
    fun observeTermPeriods(): Flow<List<TermPeriod>> =
        planDao.observeTermPeriods().map { list -> list.map { it.toModel() } }

    suspend fun addTermPeriod(term: TermPeriod): Long {
        val id = planDao.insertTermPeriod(term.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    suspend fun updateTermPeriod(term: TermPeriod) {
        planDao.updateTermPeriod(term.toEntity())
        widgetUpdater.requestUpdate()
    }

    suspend fun deleteTermPeriod(id: Long) {
        planDao.deleteTermPeriod(id)
        widgetUpdater.requestUpdate()
    }

    // Predictions (upsert by category + regime; deleting one reverts the category to automatic).
    fun observePredictions(): Flow<List<Prediction>> =
        planDao.observePredictions().map { list -> list.map { it.toModel() } }

    suspend fun upsertPrediction(prediction: Prediction) {
        val existing = planDao.getPrediction(prediction.categoryId, prediction.regime.name)
        if (existing != null) {
            planDao.updatePrediction(prediction.copy(id = existing.id).toEntity())
        } else {
            planDao.insertPrediction(prediction.toEntity())
        }
        widgetUpdater.requestUpdate()
    }

    suspend fun deletePrediction(categoryId: Long, regime: Regime) {
        planDao.deletePrediction(categoryId, regime.name)
        widgetUpdater.requestUpdate()
    }

    // Sinking funds.
    fun observeSinkingFunds(): Flow<List<SinkingFund>> =
        planDao.observeSinkingFunds().map { list -> list.map { it.toModel() } }

    suspend fun addSinkingFund(fund: SinkingFund): Long {
        val id = planDao.insertSinkingFund(fund.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    suspend fun updateSinkingFund(fund: SinkingFund) {
        planDao.updateSinkingFund(fund.toEntity())
        widgetUpdater.requestUpdate()
    }

    suspend fun deleteSinkingFund(id: Long) {
        planDao.deleteSinkingFund(id)
        widgetUpdater.requestUpdate()
    }
}
