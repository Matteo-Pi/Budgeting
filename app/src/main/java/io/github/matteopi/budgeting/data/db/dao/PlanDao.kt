package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.PredictionEntity
import io.github.matteopi.budgeting.data.db.entities.RecurringRuleEntity
import io.github.matteopi.budgeting.data.db.entities.ScheduledItemEntity
import io.github.matteopi.budgeting.data.db.entities.SinkingFundEntity
import io.github.matteopi.budgeting.data.db.entities.TermPeriodEntity
import kotlinx.coroutines.flow.Flow

/** Recurring rules, scheduled items, term periods, predictions and sinking funds. */
@Dao
interface PlanDao {
    // Recurring rules.
    @Query("SELECT * FROM recurring_rules ORDER BY label")
    fun observeRecurringRules(): Flow<List<RecurringRuleEntity>>

    @Insert
    suspend fun insertRecurringRule(rule: RecurringRuleEntity): Long

    @Insert
    suspend fun insertRecurringRules(rules: List<RecurringRuleEntity>): List<Long>

    @Update
    suspend fun updateRecurringRule(rule: RecurringRuleEntity)

    @Query("DELETE FROM recurring_rules WHERE id = :id")
    suspend fun deleteRecurringRule(id: Long)

    @Query("DELETE FROM recurring_rules")
    suspend fun deleteAllRecurringRules()

    // Scheduled items.
    @Query("SELECT * FROM scheduled_items ORDER BY date")
    fun observeScheduledItems(): Flow<List<ScheduledItemEntity>>

    @Insert
    suspend fun insertScheduledItem(item: ScheduledItemEntity): Long

    @Insert
    suspend fun insertScheduledItems(items: List<ScheduledItemEntity>): List<Long>

    @Update
    suspend fun updateScheduledItem(item: ScheduledItemEntity)

    @Query("DELETE FROM scheduled_items WHERE id = :id")
    suspend fun deleteScheduledItem(id: Long)

    @Query("DELETE FROM scheduled_items")
    suspend fun deleteAllScheduledItems()

    // Term periods.
    @Query("SELECT * FROM term_periods ORDER BY start_date")
    fun observeTermPeriods(): Flow<List<TermPeriodEntity>>

    @Insert
    suspend fun insertTermPeriod(term: TermPeriodEntity): Long

    @Insert
    suspend fun insertTermPeriods(terms: List<TermPeriodEntity>): List<Long>

    @Update
    suspend fun updateTermPeriod(term: TermPeriodEntity)

    @Query("DELETE FROM term_periods WHERE id = :id")
    suspend fun deleteTermPeriod(id: Long)

    @Query("DELETE FROM term_periods")
    suspend fun deleteAllTermPeriods()

    // Predictions.
    @Query("SELECT * FROM predictions")
    fun observePredictions(): Flow<List<PredictionEntity>>

    @Query("SELECT * FROM predictions WHERE category_id = :categoryId AND regime = :regime LIMIT 1")
    suspend fun getPrediction(categoryId: Long, regime: String): PredictionEntity?

    @Insert
    suspend fun insertPrediction(prediction: PredictionEntity): Long

    @Insert
    suspend fun insertPredictions(predictions: List<PredictionEntity>): List<Long>

    @Update
    suspend fun updatePrediction(prediction: PredictionEntity)

    @Query("DELETE FROM predictions WHERE category_id = :categoryId AND regime = :regime")
    suspend fun deletePrediction(categoryId: Long, regime: String)

    @Query("DELETE FROM predictions")
    suspend fun deleteAllPredictions()

    // Sinking funds.
    @Query("SELECT * FROM sinking_funds ORDER BY target_date")
    fun observeSinkingFunds(): Flow<List<SinkingFundEntity>>

    @Insert
    suspend fun insertSinkingFund(fund: SinkingFundEntity): Long

    @Insert
    suspend fun insertSinkingFunds(funds: List<SinkingFundEntity>): List<Long>

    @Update
    suspend fun updateSinkingFund(fund: SinkingFundEntity)

    @Query("DELETE FROM sinking_funds WHERE id = :id")
    suspend fun deleteSinkingFund(id: Long)

    @Query("DELETE FROM sinking_funds")
    suspend fun deleteAllSinkingFunds()
}
