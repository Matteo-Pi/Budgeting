package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.planner.TimeCategoryEntity
import io.github.matteopi.budgeting.data.db.entities.planner.DayReviewEntity
import io.github.matteopi.budgeting.data.db.entities.planner.PlanBlockEntity
import io.github.matteopi.budgeting.data.db.entities.planner.RecordEntity
import kotlinx.coroutines.flow.Flow

/** Minutes recorded against one task. */
data class TaskMillis(val taskId: Long, val millis: Long)

/**
 * The day record (docs/PLAN.md §13.5): categories, plan blocks, records and reviews. A range read is
 * `start < :to AND end > :from`, served by the start and end indices.
 */
@Dao
abstract class DayDao {
    // ---- Categories ---------------------------------------------------------------------------

    @Query("SELECT * FROM planner_categories ORDER BY archived, sort_order, id")
    abstract fun observeCategories(): Flow<List<TimeCategoryEntity>>

    @Query("SELECT * FROM planner_categories ORDER BY archived, sort_order, id")
    abstract suspend fun getCategories(): List<TimeCategoryEntity>

    @Insert
    abstract suspend fun insertCategories(categories: List<TimeCategoryEntity>): List<Long>

    @Insert
    abstract suspend fun insertCategory(category: TimeCategoryEntity): Long

    @Update
    abstract suspend fun updateCategory(category: TimeCategoryEntity): Int

    // ---- Records ------------------------------------------------------------------------------

    @Query("SELECT * FROM planner_records WHERE start_millis < :to AND end_millis > :from ORDER BY start_millis")
    abstract fun observeRecords(from: Long, to: Long): Flow<List<RecordEntity>>

    @Query("SELECT * FROM planner_records WHERE start_millis < :to AND end_millis > :from ORDER BY start_millis")
    abstract suspend fun getRecords(from: Long, to: Long): List<RecordEntity>

    @Insert
    abstract suspend fun insertRecords(records: List<RecordEntity>): List<Long>

    @Update
    abstract suspend fun updateRecords(records: List<RecordEntity>)

    @Query("DELETE FROM planner_records WHERE id IN (:ids)")
    abstract suspend fun deleteRecords(ids: List<Long>)

    /** One edit of the records: deletes, then updates, then inserts (returning their ids), together. */
    @Transaction
    open suspend fun applyRecords(inserts: List<RecordEntity>, updates: List<RecordEntity>, deleteIds: List<Long>): List<Long> {
        if (deleteIds.isNotEmpty()) deleteRecords(deleteIds)
        if (updates.isNotEmpty()) updateRecords(updates)
        return if (inserts.isEmpty()) emptyList() else insertRecords(inserts)
    }

    @Query(
        "SELECT task_id AS taskId, SUM(end_millis - start_millis) AS millis FROM planner_records " +
            "WHERE task_id IS NOT NULL GROUP BY task_id",
    )
    abstract fun observeTimeByTask(): Flow<List<TaskMillis>>

    // ---- Plan blocks --------------------------------------------------------------------------

    @Query("SELECT * FROM planner_plan_blocks WHERE start_millis < :to AND end_millis > :from ORDER BY start_millis")
    abstract fun observePlans(from: Long, to: Long): Flow<List<PlanBlockEntity>>

    @Query("SELECT * FROM planner_plan_blocks WHERE start_millis < :to AND end_millis > :from ORDER BY start_millis")
    abstract suspend fun getPlans(from: Long, to: Long): List<PlanBlockEntity>

    @Insert
    abstract suspend fun insertPlans(plans: List<PlanBlockEntity>): List<Long>

    @Update
    abstract suspend fun updatePlans(plans: List<PlanBlockEntity>)

    @Query("DELETE FROM planner_plan_blocks WHERE id IN (:ids)")
    abstract suspend fun deletePlans(ids: List<Long>)

    @Transaction
    open suspend fun applyPlans(inserts: List<PlanBlockEntity>, updates: List<PlanBlockEntity>, deleteIds: List<Long>): List<Long> {
        if (deleteIds.isNotEmpty()) deletePlans(deleteIds)
        if (updates.isNotEmpty()) updatePlans(updates)
        return if (inserts.isEmpty()) emptyList() else insertPlans(inserts)
    }

    // ---- Reviews ------------------------------------------------------------------------------

    @Query("SELECT * FROM planner_day_reviews WHERE date BETWEEN :from AND :to ORDER BY date")
    abstract fun observeReviews(from: Long, to: Long): Flow<List<DayReviewEntity>>

    @Query("SELECT * FROM planner_day_reviews WHERE date BETWEEN :from AND :to ORDER BY date")
    abstract suspend fun getReviews(from: Long, to: Long): List<DayReviewEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertReview(review: DayReviewEntity)

    // ---- Backups ------------------------------------------------------------------------------

    @Query("SELECT * FROM planner_records ORDER BY id")
    abstract suspend fun getAllRecords(): List<RecordEntity>

    @Query("SELECT * FROM planner_plan_blocks ORDER BY id")
    abstract suspend fun getAllPlans(): List<PlanBlockEntity>

    @Query("SELECT * FROM planner_day_reviews ORDER BY date")
    abstract suspend fun getAllReviews(): List<DayReviewEntity>

    @Insert
    abstract suspend fun insertReviews(reviews: List<DayReviewEntity>)

    @Query("DELETE FROM planner_records")
    abstract suspend fun deleteAllRecords()

    @Query("DELETE FROM planner_plan_blocks")
    abstract suspend fun deleteAllPlans()

    @Query("DELETE FROM planner_day_reviews")
    abstract suspend fun deleteAllReviews()

    @Query("DELETE FROM planner_categories")
    abstract suspend fun deleteAllCategories()
}
