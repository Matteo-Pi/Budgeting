package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.planner.DismissedEntity
import io.github.matteopi.budgeting.data.db.entities.planner.RevisionTopicEntity
import kotlinx.coroutines.flow.Flow

/** Exams' revision topics and the dismissed suggestions (docs/PLAN.md §13.5). */
@Dao
interface StudyDao {
    @Query("SELECT * FROM planner_revision_topics ORDER BY exam_task_id, sort_order, id")
    fun observeTopics(): Flow<List<RevisionTopicEntity>>

    @Query("SELECT * FROM planner_revision_topics ORDER BY exam_task_id, sort_order, id")
    suspend fun getTopics(): List<RevisionTopicEntity>

    @Insert
    suspend fun insertTopic(topic: RevisionTopicEntity): Long

    @Update
    suspend fun updateTopic(topic: RevisionTopicEntity)

    @Query("DELETE FROM planner_revision_topics WHERE id = :id")
    suspend fun deleteTopic(id: Long)

    @Query("SELECT * FROM planner_dismissed")
    fun observeDismissed(): Flow<List<DismissedEntity>>

    @Query("SELECT * FROM planner_dismissed")
    suspend fun getDismissed(): List<DismissedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dismiss(entry: DismissedEntity)

    @Query("DELETE FROM planner_dismissed WHERE `key` = :key")
    suspend fun pruneDismissedKey(key: String)

    @Query("DELETE FROM planner_dismissed WHERE dismissed_at_millis < :before")
    suspend fun pruneDismissed(before: Long)

    // ---- Backups ------------------------------------------------------------------------------

    @Insert
    suspend fun insertTopics(topics: List<RevisionTopicEntity>)

    @Insert
    suspend fun insertDismissed(entries: List<DismissedEntity>)

    @Query("DELETE FROM planner_revision_topics")
    suspend fun deleteAllTopics()

    @Query("DELETE FROM planner_dismissed")
    suspend fun deleteAllDismissed()
}
