package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A topic to revise for an exam (docs/PLAN.md §13.5); it goes with its exam. */
@Entity(
    tableName = "planner_revision_topics",
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["exam_task_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("exam_task_id")],
)
data class RevisionTopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "exam_task_id") val examTaskId: Long,
    val name: String,
    val difficulty: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
