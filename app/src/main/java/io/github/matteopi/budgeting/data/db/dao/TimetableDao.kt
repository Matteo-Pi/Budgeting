package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.planner.CalendarLinkEntity
import io.github.matteopi.budgeting.data.db.entities.planner.EntryExceptionEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TimetableEntryEntity
import kotlinx.coroutines.flow.Flow

/** The timetable, its one-date changes and the calendar links (docs/PLAN.md §13.5). */
@Dao
abstract class TimetableDao {
    // ---- Entries ------------------------------------------------------------------------------

    @Query("SELECT * FROM planner_entries ORDER BY archived, weekday, start_minute, id")
    abstract fun observeEntries(): Flow<List<TimetableEntryEntity>>

    @Query("SELECT * FROM planner_entries ORDER BY archived, weekday, start_minute, id")
    abstract suspend fun getEntries(): List<TimetableEntryEntity>

    @Query("SELECT * FROM planner_entries WHERE id = :id")
    abstract suspend fun getEntry(id: Long): TimetableEntryEntity?

    @Insert
    abstract suspend fun insertEntry(entry: TimetableEntryEntity): Long

    @Insert
    abstract suspend fun insertEntries(entries: List<TimetableEntryEntity>): List<Long>

    /** Returns the number of rows changed: 0 when there is no such entry. */
    @Update
    abstract suspend fun updateEntry(entry: TimetableEntryEntity): Int

    @Query("DELETE FROM planner_entries WHERE id = :id")
    abstract suspend fun deleteEntry(id: Long)

    // ---- One-date changes ---------------------------------------------------------------------

    @Query("SELECT * FROM planner_entry_exceptions ORDER BY date, entry_id")
    abstract fun observeExceptions(): Flow<List<EntryExceptionEntity>>

    @Query("SELECT * FROM planner_entry_exceptions ORDER BY date, entry_id")
    abstract suspend fun getExceptions(): List<EntryExceptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertException(exception: EntryExceptionEntity): Long

    @Insert
    abstract suspend fun insertExceptions(exceptions: List<EntryExceptionEntity>)

    @Query("DELETE FROM planner_entry_exceptions WHERE entry_id = :entryId AND date = :date")
    abstract suspend fun deleteException(entryId: Long, date: Long)

    /** Sets the change of [exception]'s entry on its date, replacing any it had. */
    @Transaction
    open suspend fun setException(exception: EntryExceptionEntity) {
        deleteException(exception.entryId, exception.date)
        upsertException(exception.copy(id = 0))
    }

    // ---- Calendar links -----------------------------------------------------------------------

    @Query("SELECT * FROM planner_calendar_links ORDER BY id")
    abstract suspend fun getLinks(): List<CalendarLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLinks(links: List<CalendarLinkEntity>)

    @Query("DELETE FROM planner_calendar_links WHERE id IN (:ids)")
    abstract suspend fun deleteLinks(ids: List<Long>)

    @Query("DELETE FROM planner_calendar_links WHERE item_key IN (:keys)")
    abstract suspend fun deleteLinksForKeys(keys: List<String>)

    @Query("SELECT event_id FROM planner_calendar_links")
    abstract fun observeLinkedEventIds(): Flow<List<Long>>

    // ---- Backups ------------------------------------------------------------------------------

    @Query("DELETE FROM planner_calendar_links")
    abstract suspend fun deleteAllLinks()

    @Query("DELETE FROM planner_entry_exceptions")
    abstract suspend fun deleteAllExceptions()

    @Query("DELETE FROM planner_entries")
    abstract suspend fun deleteAllEntries()
}
