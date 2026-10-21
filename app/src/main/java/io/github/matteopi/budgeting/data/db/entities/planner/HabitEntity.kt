package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A habit (docs/PLAN.md §13.5): its goal as `goal_kind`, `target`, `unit`, `period` and `days` (a weekday bitmask,
 * Monday = bit 0), dates as epoch days and times of day as minutes. Archived, never deleted, while plan blocks point
 * at it; its category is archived, never deleted, too.
 */
@Entity(
    tableName = "planner_habits",
    foreignKeys = [ForeignKey(entity = TimeCategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"])],
    indices = [Index("category_id")],
)
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    @ColumnInfo(name = "goal_kind") val goalKind: String,
    val target: Int,
    val unit: String,
    val period: String,
    val days: Int,
    val counting: String,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "min_minutes") val minMinutes: Int?,
    val streaks: Boolean,
    @ColumnInfo(name = "reminder_minute") val reminderMinute: Int?,
    @ColumnInfo(name = "term_only") val termOnly: Boolean,
    @ColumnInfo(name = "paused_from") val pausedFrom: Long?,
    @ColumnInfo(name = "paused_until") val pausedUntil: Long?,
    @ColumnInfo(name = "suggest_slot") val suggestSlot: Boolean,
    @ColumnInfo(name = "slot_minutes") val slotMinutes: Int?,
    @ColumnInfo(name = "slot_start_minute") val slotStartMinute: Int?,
    @ColumnInfo(name = "slot_end_minute") val slotEndMinute: Int?,
    @ColumnInfo(name = "start_date") val startDate: Long,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
