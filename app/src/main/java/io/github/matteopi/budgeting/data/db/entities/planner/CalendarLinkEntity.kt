package io.github.matteopi.budgeting.data.db.entities.planner

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An event the app wrote to a calendar on the phone (docs/PLAN.md §13.7): `item_key` is what it shows ("task:4",
 * "entry:12:2026-10-20"), `uid` the event's UID, kept so a restore or a new phone re-links instead of writing twice.
 */
@Entity(
    tableName = "planner_calendar_links",
    indices = [Index(value = ["item_key"], unique = true), Index("event_id")],
)
data class CalendarLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "event_id") val eventId: Long,
    val uid: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
)
