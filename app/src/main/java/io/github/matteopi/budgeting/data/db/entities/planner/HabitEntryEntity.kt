package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A habit's day filled in by hand (`date` an epoch day): one per habit and day; it goes with its habit. */
@Entity(
    tableName = "planner_habit_entries",
    foreignKeys = [
        ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habit_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["habit_id", "date"], unique = true)],
)
data class HabitEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "habit_id") val habitId: Long,
    val date: Long,
    val amount: Int,
    val skipped: Boolean,
)
