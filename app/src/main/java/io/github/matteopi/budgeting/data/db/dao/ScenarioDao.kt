package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.ScenarioEntity
import io.github.matteopi.budgeting.data.db.entities.ScenarioItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScenarioDao {
    @Query("SELECT * FROM scenarios ORDER BY created_at DESC")
    fun observeScenarios(): Flow<List<ScenarioEntity>>

    @Query("SELECT * FROM scenarios WHERE id = :id")
    suspend fun get(id: Long): ScenarioEntity?

    @Insert
    suspend fun insertScenario(scenario: ScenarioEntity): Long

    @Update
    suspend fun updateScenario(scenario: ScenarioEntity)

    @Query("DELETE FROM scenarios WHERE id = :id")
    suspend fun deleteScenario(id: Long)

    @Query("DELETE FROM scenarios")
    suspend fun deleteAllScenarios()

    @Query("SELECT * FROM scenario_items")
    fun observeScenarioItems(): Flow<List<ScenarioItemEntity>>

    @Insert
    suspend fun insertItems(items: List<ScenarioItemEntity>): List<Long>

    @Query("DELETE FROM scenario_items WHERE scenario_id = :scenarioId")
    suspend fun deleteItemsForScenario(scenarioId: Long)

    @Query("DELETE FROM scenario_items")
    suspend fun deleteAllItems()
}
