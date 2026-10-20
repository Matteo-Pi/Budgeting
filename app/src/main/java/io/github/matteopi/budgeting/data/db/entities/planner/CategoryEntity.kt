package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A day-record category (docs/PLAN.md §13.5). `system_key` (a `CategoryKey` name) marks the defaults so the rules
 * find them after a rename; the user's own have none. Archived, never deleted, while chunks point at it.
 */
@Entity(tableName = "planner_categories", indices = [Index(value = ["system_key"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "rates_focus") val ratesFocus: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
