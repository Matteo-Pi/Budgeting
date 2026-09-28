package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.PresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY sort_order")
    fun observeAll(): Flow<List<PresetEntity>>

    @Insert
    suspend fun insert(preset: PresetEntity): Long

    @Insert
    suspend fun insertAll(presets: List<PresetEntity>): List<Long>

    @Update
    suspend fun update(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM presets")
    suspend fun deleteAll()

    @Query("UPDATE presets SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)
}
