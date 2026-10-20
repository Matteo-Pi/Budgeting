package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A university module (docs/PLAN.md §13.5). `code_key` is the code in lower case: codes are unique without
 * regard to case.
 */
@Entity(
    tableName = "planner_modules",
    indices = [Index(value = ["code_key"], unique = true)],
)
data class ModuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    @ColumnInfo(name = "code_key") val codeKey: String,
    val name: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long?,
    @ColumnInfo(name = "weekly_target_minutes") val weeklyTargetMinutes: Int?,
    val weight: Int,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
