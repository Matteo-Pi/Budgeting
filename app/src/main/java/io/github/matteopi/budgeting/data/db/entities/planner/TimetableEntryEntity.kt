package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.matteopi.budgeting.data.db.entities.TermPeriodEntity

/**
 * A timetable entry (docs/PLAN.md §13.5): `weekday` 1 (Monday) to 7, times in minutes after midnight, `pattern` a
 * week-pattern kind (EVERY, WEEKS, ODD, EVEN, ONCE) with its `weeks` ("1–5, 7–11") or `once_date` (epoch day).
 * Deleting its module or term keeps it (for every module, every term).
 */
@Entity(
    tableName = "planner_entries",
    foreignKeys = [
        ForeignKey(entity = ModuleEntity::class, parentColumns = ["id"], childColumns = ["module_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = TermPeriodEntity::class, parentColumns = ["id"], childColumns = ["term_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("module_id"), Index("term_id")],
)
data class TimetableEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: String,
    @ColumnInfo(name = "module_id") val moduleId: Long?,
    val location: String,
    val room: String,
    val contact: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long?,
    val note: String,
    val weekday: Int,
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    @ColumnInfo(name = "end_minute") val endMinute: Int,
    val pattern: String,
    val weeks: String,
    @ColumnInfo(name = "once_date") val onceDate: Long?,
    @ColumnInfo(name = "term_id") val termId: Long?,
    @ColumnInfo(name = "in_holidays") val inHolidays: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)
