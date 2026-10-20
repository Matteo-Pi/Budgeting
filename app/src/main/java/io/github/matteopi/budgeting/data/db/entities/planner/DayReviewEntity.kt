package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A day's end-of-day review (docs/PLAN.md §13.5): `date` is an epoch day. */
@Entity(tableName = "planner_day_reviews")
data class DayReviewEntity(
    @PrimaryKey val date: Long,
    @ColumnInfo(name = "finished_at_millis") val finishedAtMillis: Long?,
    val rating: Int?,
    val note: String,
)
