package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A dismissed suggestion's key (docs/PLAN.md §13.5), pruned 30 days after it was dismissed. */
@Entity(tableName = "planner_dismissed")
data class DismissedEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "dismissed_at_millis") val dismissedAtMillis: Long,
)
