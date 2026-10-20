package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One date of a timetable entry changed or cancelled (`date` an epoch day); it goes with its entry. */
@Entity(
    tableName = "planner_entry_exceptions",
    foreignKeys = [
        ForeignKey(entity = TimetableEntryEntity::class, parentColumns = ["id"], childColumns = ["entry_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["entry_id", "date"], unique = true)],
)
data class EntryExceptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entry_id") val entryId: Long,
    val date: Long,
    val cancelled: Boolean,
    @ColumnInfo(name = "start_minute") val startMinute: Int?,
    @ColumnInfo(name = "end_minute") val endMinute: Int?,
    val room: String?,
)
