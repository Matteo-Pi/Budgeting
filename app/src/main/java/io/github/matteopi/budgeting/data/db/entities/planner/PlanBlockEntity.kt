package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Time planned ahead (docs/PLAN.md §13.5), as epoch millis. `habit_id` and `topic_id` are plain ids: their tables
 * come in later versions, and their repositories clear them. Deleting a task or module keeps the block.
 */
@Entity(
    tableName = "planner_plan_blocks",
    foreignKeys = [
        ForeignKey(entity = TimeCategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"]),
        ForeignKey(entity = ModuleEntity::class, parentColumns = ["id"], childColumns = ["module_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("start_millis"), Index("end_millis"), Index("category_id"), Index("module_id"), Index("task_id")],
)
data class PlanBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_millis") val startMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "module_id") val moduleId: Long?,
    @ColumnInfo(name = "task_id") val taskId: Long?,
    @ColumnInfo(name = "habit_id") val habitId: Long?,
    @ColumnInfo(name = "topic_id") val topicId: Long?,
    val origin: String,
    @ColumnInfo(name = "outcome_override") val outcomeOverride: String?,
    val note: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)
