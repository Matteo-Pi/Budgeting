package io.github.matteopi.budgeting.data.repo.planner

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.timetable.TermCopy
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.TimetableDao
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The timetable (docs/PLAN.md §13.5): entries, their one-date changes, copying a term, and the links to events the app
 * wrote into a calendar. Every write tells [PlannerUpdater], so reminders and the calendar sync follow.
 */
class TimetableRepository(
    private val database: BudgetDatabase,
    private val timetableDao: TimetableDao,
    private val clock: Clock,
    private val plannerUpdater: PlannerUpdater,
) {
    fun observeEntries(): Flow<List<TimetableEntry>> = timetableDao.observeEntries().map { list -> list.map { it.toModel() } }

    suspend fun getEntries(): List<TimetableEntry> = timetableDao.getEntries().map { it.toModel() }

    suspend fun getEntry(id: Long): TimetableEntry? = timetableDao.getEntry(id)?.toModel()

    fun observeExceptions(): Flow<List<EntryException>> = timetableDao.observeExceptions().map { list -> list.map { it.toModel() } }

    suspend fun getExceptions(): List<EntryException> = timetableDao.getExceptions().map { it.toModel() }

    /** Inserts [entry] (id 0, created now) or updates it. Returns its id. */
    suspend fun save(entry: TimetableEntry): Long {
        val id = if (entry.id == 0L) {
            timetableDao.insertEntry(entry.copy(createdAt = Instant.ofEpochMilli(clock.nowMillis())).toEntity())
        } else {
            val changed = timetableDao.updateEntry(entry.toEntity())
            require(changed > 0) { "There is no timetable entry ${entry.id} to update" }
            entry.id
        }
        plannerUpdater.changed()
        return id
    }

    /** Deletes [id] and its changes. Returns it for *Undo*, or null if it was gone. */
    suspend fun delete(id: Long): Pair<TimetableEntry, List<EntryException>>? {
        val deleted = database.withTransaction {
            val entry = timetableDao.getEntry(id)?.toModel() ?: return@withTransaction null
            val changes = timetableDao.getExceptions().filter { it.entryId == id }.map { it.toModel() }
            timetableDao.deleteEntry(id)
            entry to changes
        }
        plannerUpdater.changed()
        return deleted
    }

    /** *Undo* of [delete]: the entry and its changes with their old ids. */
    suspend fun restore(deleted: Pair<TimetableEntry, List<EntryException>>) {
        database.withTransaction {
            timetableDao.insertEntries(listOf(deleted.first.toEntity()))
            timetableDao.insertExceptions(deleted.second.map { it.toEntity() })
        }
        plannerUpdater.changed()
    }

    /** *Cancel this week*: [entryId] doesn't happen on [date]. */
    suspend fun cancel(entryId: Long, date: LocalDate) = setException(EntryException(entryId = entryId, date = date, cancelled = true))

    /** *Change this week*: other times or another room on [date] (null keeps the entry's own). */
    suspend fun change(entryId: Long, date: LocalDate, start: LocalTime?, end: LocalTime?, room: String?) =
        setException(EntryException(entryId = entryId, date = date, start = start, end = end, room = room))

    /** *As usual*: undoes a change or cancellation on [date]. */
    suspend fun restoreDate(entryId: Long, date: LocalDate) {
        timetableDao.deleteException(entryId, date.toEpochDay())
        plannerUpdater.changed()
    }

    private suspend fun setException(exception: EntryException) {
        timetableDao.setException(exception.toEntity())
        plannerUpdater.changed()
    }

    /** *Copy from last term*: [from]'s own entries, as new entries for [to]. Returns how many were added. */
    suspend fun copyTerm(from: TermPeriod, to: TermPeriod): Int {
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val rows = TermCopy.rows(getEntries(), from, to).map { it.copy(createdAt = now).toEntity() }
        if (rows.isEmpty()) return 0
        timetableDao.insertEntries(rows)
        plannerUpdater.changed()
        return rows.size
    }

    // ---- Calendar links (the sync's bookkeeping; no updater call, or a sync would trigger another) ----

    suspend fun getLinks(): List<CalendarLink> = timetableDao.getLinks().map { it.toModel() }

    /** The ids of events the app wrote: the timeline leaves them out, as the app's own items already show. */
    fun observeLinkedEventIds(): Flow<Set<Long>> = timetableDao.observeLinkedEventIds().map { it.toSet() }

    /** Records the sync's result: [upserts] replace links by item, then [dropIds] go. */
    suspend fun updateLinks(upserts: List<CalendarLink>, dropIds: List<Long>) {
        database.withTransaction {
            if (dropIds.isNotEmpty()) timetableDao.deleteLinks(dropIds)
            if (upserts.isNotEmpty()) {
                timetableDao.deleteLinksForKeys(upserts.map { it.itemKey })
                timetableDao.upsertLinks(upserts.map { it.toEntity().copy(id = 0) })
            }
        }
    }

    suspend fun clearLinks() {
        timetableDao.deleteAllLinks()
    }
}
