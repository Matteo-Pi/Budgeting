package io.github.matteopi.budgeting.data.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import io.github.matteopi.budgeting.core.planner.calendar.CalendarSync
import io.github.matteopi.budgeting.core.planner.calendar.DesiredEvent
import io.github.matteopi.budgeting.core.planner.calendar.ExistingEvent
import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** A calendar on the phone (docs/PLAN.md §13.7). [writable] when its access level lets the app add events. */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val colorArgb: Long?,
    val writable: Boolean,
    val visible: Boolean,
)

/**
 * Reading and writing the phone's calendars through `CalendarContract` (docs/PLAN.md §13.7). Android keeps a copy of
 * the synced Google calendars on the phone, so all of this works offline. Every query runs on [Dispatchers.IO] and
 * reads only the columns it needs; a missing permission or provider reads as nothing, never a crash.
 */
class CalendarAccess(private val context: Context) {

    fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Every calendar on the phone, by account then name. */
    suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
        )
        val result = mutableListOf<CalendarInfo>()
        safely {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    result += CalendarInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        accountName = c.getString(2) ?: "",
                        accountType = c.getString(3) ?: "",
                        colorArgb = if (c.isNull(4)) null else c.getInt(4).toLong() and 0xFFFFFFFFL,
                        writable = c.getInt(5) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                        visible = c.getInt(6) != 0,
                    )
                }
            }
        }
        result.sortedWith(compareBy({ it.accountName.lowercase() }, { it.name.lowercase() }))
    }

    /**
     * The events of [calendarIds] from [from] to [to]: Android expands repeating events and their exceptions itself.
     * Cancelled ones are left out. All-day events are dated by UTC midnight, as the provider stores them.
     */
    suspend fun instances(from: Instant, to: Instant, calendarIds: Set<Long>, zone: ZoneId): List<CalendarInstance> =
        withContext(Dispatchers.IO) {
            if (!canRead() || calendarIds.isEmpty() || !to.isAfter(from)) return@withContext emptyList()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, from.toEpochMilli())
                ContentUris.appendId(it, to.toEpochMilli())
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.STATUS,
            )
            val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})"
            val result = mutableListOf<CalendarInstance>()
            safely {
                context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                    while (c.moveToNext()) {
                        if (!c.isNull(9) && c.getInt(9) == CalendarContract.Events.STATUS_CANCELED) continue
                        val allDay = c.getInt(6) != 0
                        val begin = Instant.ofEpochMilli(c.getLong(4))
                        val end = Instant.ofEpochMilli(if (c.isNull(5)) c.getLong(4) else c.getLong(5))
                        result += CalendarInstance(
                            eventId = c.getLong(0),
                            calendarId = c.getLong(1),
                            title = c.getString(2) ?: "",
                            location = c.getString(3) ?: "",
                            start = if (allDay) begin.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant() else begin,
                            end = if (allDay) end.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant() else end,
                            allDay = allDay,
                            busy = c.isNull(7) || c.getInt(7) != CalendarContract.Events.AVAILABILITY_FREE,
                            colorArgb = if (c.isNull(8)) null else c.getInt(8).toLong() and 0xFFFFFFFFL,
                        )
                    }
                }
            }
            result
        }

    /** A signal on every change to the phone's calendars (a sync, an edit), conflated: the first comes straight away. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        }.isSuccess
        trySend(Unit)
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    /**
     * The events the app wrote to [calendarId]: those with a Planner UID, and any of [eventIds] that still carry the
     * app's description (a link restored from another phone may hold an id that is one of this phone's own events, and
     * those must never be touched).
     */
    suspend fun appEvents(calendarId: Long, eventIds: Collection<Long>): List<ExistingEvent> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.UID_2445,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
        )
        val ids = if (eventIds.isEmpty()) {
            ""
        } else {
            " OR (${CalendarContract.Events._ID} IN (${eventIds.joinToString(",")}) AND ${CalendarContract.Events.DESCRIPTION} LIKE ?)"
        }
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0 AND " +
            "(${CalendarContract.Events.UID_2445} LIKE ?$ids)"
        val args = listOfNotNull(
            calendarId.toString(),
            "${CalendarSync.UID_PREFIX}%${CalendarSync.UID_SUFFIX}",
            if (eventIds.isEmpty()) null else "%${CalendarSync.DESCRIPTION}%",
        ).toTypedArray()
        val result = mutableListOf<ExistingEvent>()
        safely {
            context.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val start = c.getLong(3)
                    result += ExistingEvent(
                        eventId = c.getLong(0),
                        uid = c.getString(1),
                        title = c.getString(2) ?: "",
                        start = Instant.ofEpochMilli(start),
                        end = Instant.ofEpochMilli(if (c.isNull(4)) start else c.getLong(4)),
                        location = c.getString(5) ?: "",
                        description = c.getString(6) ?: "",
                    )
                }
            }
        }
        result
    }

    /**
     * Applies a sync to [calendarId] in batches of [BATCH]: inserts (returning each new event's id by key), updates and
     * deletes. Returns null when the provider refused (no permission, the calendar gone).
     */
    suspend fun apply(
        calendarId: Long,
        inserts: List<DesiredEvent>,
        updates: List<Pair<Long, DesiredEvent>>,
        deletes: List<Long>,
        zone: ZoneId,
    ): Map<String, Long>? = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext null
        val inserted = LinkedHashMap<String, Long>()
        val ok = safely {
            for (chunk in inserts.chunked(BATCH)) {
                val ops = ArrayList(chunk.map { ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values(it, calendarId, zone)).build() })
                val results = context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
                chunk.zip(results.toList()).forEach { (event, result) -> result.uri?.let { inserted[event.key] = ContentUris.parseId(it) } }
            }
            for (chunk in updates.chunked(BATCH)) {
                val ops = ArrayList(chunk.map { (id, event) -> ContentProviderOperation.newUpdate(eventUri(id)).withValues(values(event, calendarId, zone)).build() })
                context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            }
            for (chunk in deletes.chunked(BATCH)) {
                val ops = ArrayList(chunk.map { ContentProviderOperation.newDelete(eventUri(it)).build() })
                context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            }
        }
        if (ok) inserted else null
    }

    private fun eventUri(id: Long): Uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)

    private fun values(event: DesiredEvent, calendarId: Long, zone: ZoneId) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, event.title)
        put(CalendarContract.Events.DTSTART, event.start.toEpochMilli())
        put(CalendarContract.Events.DTEND, event.end.toEpochMilli())
        put(CalendarContract.Events.EVENT_LOCATION, event.location)
        put(CalendarContract.Events.DESCRIPTION, event.description)
        put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        put(CalendarContract.Events.UID_2445, event.uid)
        put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
    }

    /** Runs [block], treating the platform's documented failures (no permission, no provider, a bad id) as "didn't". */
    private inline fun safely(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: android.content.OperationApplicationException) {
        false
    } catch (e: android.os.RemoteException) {
        false
    } catch (e: android.database.SQLException) {
        false
    }

    companion object {
        const val BATCH = 50
    }
}
