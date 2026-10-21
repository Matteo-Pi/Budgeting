package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.planner.HabitEntity
import io.github.matteopi.budgeting.data.db.entities.planner.HabitEntryEntity
import kotlinx.coroutines.flow.Flow

/** Habits and the days filled in by hand (docs/PLAN.md §13.5). */
@Dao
interface HabitDao {
    @Query("SELECT * FROM planner_habits ORDER BY archived, sort_order, id")
    fun observeHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM planner_habits ORDER BY archived, sort_order, id")
    suspend fun getHabits(): List<HabitEntity>

    @Query("SELECT * FROM planner_habits WHERE id = :id")
    suspend fun getHabit(id: Long): HabitEntity?

    @Insert
    suspend fun insertHabit(habit: HabitEntity): Long

    @Update
    suspend fun updateHabit(habit: HabitEntity)

    @Query("SELECT * FROM planner_habit_entries WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeEntries(from: Long, to: Long): Flow<List<HabitEntryEntity>>

    @Query("SELECT * FROM planner_habit_entries WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun getEntries(from: Long, to: Long): List<HabitEntryEntity>

    @Query("SELECT * FROM planner_habit_entries WHERE habit_id = :habitId AND date = :date")
    suspend fun getEntry(habitId: Long, date: Long): HabitEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: HabitEntryEntity): Long

    @Query("DELETE FROM planner_habit_entries WHERE habit_id = :habitId AND date = :date")
    suspend fun deleteEntry(habitId: Long, date: Long)

    // ---- Backups ------------------------------------------------------------------------------

    @Query("SELECT * FROM planner_habit_entries ORDER BY id")
    suspend fun getAllEntries(): List<HabitEntryEntity>

    @Insert
    suspend fun insertHabits(habits: List<HabitEntity>)

    @Insert
    suspend fun insertEntries(entries: List<HabitEntryEntity>)

    @Query("DELETE FROM planner_habit_entries")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM planner_habits")
    suspend fun deleteAllHabits()
}
