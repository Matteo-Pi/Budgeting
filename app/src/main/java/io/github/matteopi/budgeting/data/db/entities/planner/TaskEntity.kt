package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A to-do item, deadline or step (docs/PLAN.md §13.5). `due_date` is an epoch day, `due_minute` minutes after
 * midnight, the moments epoch millis, and `repeat_days` a weekday bitmask (Monday = bit 0). Deleting a module
 * keeps its tasks; deleting a deadline deletes its steps.
 */
@Entity(
    tableName = "planner_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ModuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["module_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("due_date"), Index("module_id"), Index("parent_id"), Index("completed_at_millis")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String,
    val kind: String,
    @ColumnInfo(name = "deadline_type") val deadlineType: String?,
    @ColumnInfo(name = "due_date") val dueDate: Long?,
    @ColumnInfo(name = "due_minute") val dueMinute: Int?,
    @ColumnInfo(name = "length_minutes") val lengthMinutes: Int?,
    @ColumnInfo(name = "estimate_minutes") val estimateMinutes: Int?,
    val priority: String,
    @ColumnInfo(name = "module_id") val moduleId: Long?,
    /** Comma-separated, like recipe tags. */
    val tags: String,
    @ColumnInfo(name = "repeat_kind") val repeatKind: String?,
    @ColumnInfo(name = "repeat_interval") val repeatInterval: Int,
    @ColumnInfo(name = "repeat_days") val repeatDays: Int,
    @ColumnInfo(name = "reminder_minutes_before") val reminderMinutesBefore: Int?,
    @ColumnInfo(name = "parent_id") val parentId: Long?,
    val location: String?,
    @ColumnInfo(name = "completed_at_millis") val completedAtMillis: Long?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
