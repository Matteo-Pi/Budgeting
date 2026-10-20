package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Time recorded (docs/PLAN.md §13.5), as epoch millis. `source_key` names the class or event it confirmed. Deleting a
 * task, module or plan block keeps the time recorded against it.
 */
@Entity(
    tableName = "planner_records",
    foreignKeys = [
        ForeignKey(entity = TimeCategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"]),
        ForeignKey(entity = ModuleEntity::class, parentColumns = ["id"], childColumns = ["module_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = PlanBlockEntity::class, parentColumns = ["id"], childColumns = ["plan_block_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("start_millis"), Index("end_millis"), Index("category_id"), Index("module_id"), Index("task_id"), Index("plan_block_id"),
        Index("source_key"),
    ],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_millis") val startMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "module_id") val moduleId: Long?,
    @ColumnInfo(name = "task_id") val taskId: Long?,
    @ColumnInfo(name = "plan_block_id") val planBlockId: Long?,
    @ColumnInfo(name = "source_key") val sourceKey: String?,
    val focus: Int?,
    val note: String,
    val origin: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)
