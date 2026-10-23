package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import io.github.matteopi.budgeting.data.db.entities.FoodDayEntity
import io.github.matteopi.budgeting.data.db.entities.FoodLogEntity
import io.github.matteopi.budgeting.data.db.entities.MealBatchEntity
import kotlinx.coroutines.flow.Flow

/**
 * The food diary, its days and the kitchen's batches (docs/PLAN.md §14.3). One user action is one transaction: the
 * `@Transaction` methods write a diary row, its sides and the batch it came from together.
 */
@Dao
abstract class DiaryDao {
    // ---- The diary ----------------------------------------------------------------------------

    @Query("SELECT * FROM food_log WHERE date = :date ORDER BY eaten_at_millis, id")
    abstract fun observeLogOn(date: Long): Flow<List<FoodLogEntity>>

    @Query("SELECT * FROM food_log WHERE date BETWEEN :from AND :to ORDER BY date, eaten_at_millis, id")
    abstract fun observeLogBetween(from: Long, to: Long): Flow<List<FoodLogEntity>>

    /** The latest rows, newest first: what *Log food* offers again and the sides last eaten with a recipe. */
    @Query("SELECT * FROM food_log ORDER BY date DESC, eaten_at_millis DESC, id DESC LIMIT :limit")
    abstract fun observeRecentLog(limit: Int): Flow<List<FoodLogEntity>>

    @Query("SELECT * FROM food_log ORDER BY date DESC, eaten_at_millis DESC, id DESC LIMIT :limit")
    abstract suspend fun getRecentLog(limit: Int): List<FoodLogEntity>

    @Query("SELECT * FROM food_log WHERE id = :id")
    abstract suspend fun getLog(id: Long): FoodLogEntity?

    @Query("SELECT * FROM food_log WHERE side_of = :id ORDER BY id")
    abstract suspend fun getSides(id: Long): List<FoodLogEntity>

    @Query("SELECT * FROM food_log ORDER BY id")
    abstract suspend fun getAllLog(): List<FoodLogEntity>

    @Insert
    abstract suspend fun insertLog(row: FoodLogEntity): Long

    @Insert
    abstract suspend fun insertLogs(rows: List<FoodLogEntity>): List<Long>

    @Update
    abstract suspend fun updateLog(row: FoodLogEntity): Int

    @Query("DELETE FROM food_log WHERE id = :id")
    abstract suspend fun deleteLog(id: Long)

    @Query("DELETE FROM food_log")
    abstract suspend fun deleteAllLog()

    // ---- The kitchen ---------------------------------------------------------------------------

    @Query("SELECT * FROM meal_batches ORDER BY made_on DESC, id DESC")
    abstract fun observeBatches(): Flow<List<MealBatchEntity>>

    @Query("SELECT * FROM meal_batches ORDER BY id")
    abstract suspend fun getBatches(): List<MealBatchEntity>

    @Query("SELECT * FROM meal_batches WHERE id = :id")
    abstract suspend fun getBatch(id: Long): MealBatchEntity?

    @Insert
    abstract suspend fun insertBatch(batch: MealBatchEntity): Long

    @Insert
    abstract suspend fun insertBatches(batches: List<MealBatchEntity>): List<Long>

    @Update
    abstract suspend fun updateBatch(batch: MealBatchEntity): Int

    @Query("DELETE FROM meal_batches WHERE id = :id")
    abstract suspend fun deleteBatch(id: Long)

    @Query("DELETE FROM meal_batches")
    abstract suspend fun deleteAllBatches()

    // ---- Days ---------------------------------------------------------------------------------

    @Query("SELECT * FROM food_days WHERE date = :date")
    abstract fun observeDay(date: Long): Flow<FoodDayEntity?>

    @Query("SELECT * FROM food_days WHERE date BETWEEN :from AND :to ORDER BY date")
    abstract fun observeDays(from: Long, to: Long): Flow<List<FoodDayEntity>>

    @Query("SELECT * FROM food_days WHERE date = :date")
    abstract suspend fun getDay(date: Long): FoodDayEntity?

    @Query("SELECT * FROM food_days ORDER BY date")
    abstract suspend fun getDays(): List<FoodDayEntity>

    @Upsert
    abstract suspend fun upsertDay(day: FoodDayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDays(days: List<FoodDayEntity>)

    @Query("DELETE FROM food_days")
    abstract suspend fun deleteAllDays()

    // ---- Writes that touch several rows -----------------------------------------------------------

    /**
     * Writes [main] and its [sides] (each pointed at [main]) and, when [batch] is given, the batch as it is after the
     * portion was taken, in one transaction. Returns [main]'s id.
     */
    @Transaction
    open suspend fun logWithSides(main: FoodLogEntity, sides: List<FoodLogEntity>, batch: MealBatchEntity?): Long {
        val id = insertLog(main)
        if (sides.isNotEmpty()) insertLogs(sides.map { it.copy(id = 0, sideOf = id) })
        if (batch != null) updateBatch(batch)
        return id
    }

    /** Deletes a row (its sides go with it) and writes [batch] as it is with the portion put back. */
    @Transaction
    open suspend fun deleteWithBatch(id: Long, batch: MealBatchEntity?) {
        deleteLog(id)
        if (batch != null) updateBatch(batch)
    }

    /** Puts deleted rows back with their own ids (Undo), and [batch] as it is with the portion taken again. */
    @Transaction
    open suspend fun restoreRows(rows: List<FoodLogEntity>, batch: MealBatchEntity?) {
        // Portions before their sides, which point at them.
        insertLogs(rows.sortedBy { if (it.sideOf == null) 0 else 1 })
        if (batch != null) updateBatch(batch)
    }

    /** Updates a row and, for a change of portions from the kitchen, its batch. */
    @Transaction
    open suspend fun updateWithBatch(row: FoodLogEntity, batch: MealBatchEntity?) {
        updateLog(row)
        if (batch != null) updateBatch(batch)
    }
}
