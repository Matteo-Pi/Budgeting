package io.github.matteopi.budgeting.testing

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import io.github.matteopi.budgeting.core.planner.calendar.CalendarSync
import io.github.matteopi.budgeting.data.settings.CalendarChoice
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A local calendar on the emulator, standing in for a synced Google one (docs/PLAN.md §13.10). The test creates it as a
 * sync adapter (`ACCOUNT_TYPE_LOCAL`), with the calendar permissions granted by its rule, and [delete]s it after, which
 * takes its events too. Events are added as a calendar app would add them.
 */
class TestCalendar private constructor(val id: Long, private val zone: ZoneId) {

    private val resolver get() = testApp.contentResolver

    val choice: CalendarChoice get() = CalendarChoice(id, ACCOUNT, NAME)

    data class Row(
        val id: Long,
        val title: String,
        val uid: String?,
        val start: Instant,
        val end: Instant,
        val location: String,
        val description: String,
    )

    /** One of the user's own events. */
    fun addEvent(title: String, start: LocalDateTime, end: LocalDateTime): Long = insert(
        ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        },
    )

    /** An all-day event: stored from UTC midnight to UTC midnight, as calendar apps do. */
    fun addAllDay(title: String, date: LocalDate): Long = insert(
        ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        },
    )

    /** A daily event for [days] days from [start], an hour long. */
    fun addDaily(title: String, start: LocalDateTime, days: Int): Long = insert(
        ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DURATION, "PT1H")
            put(CalendarContract.Events.RRULE, "FREQ=DAILY;COUNT=$days")
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        },
    )

    /** Moves the occurrence of repeating [eventId] that was due at [original] to [start]–[end]. */
    fun moveOccurrence(eventId: Long, original: LocalDateTime, start: LocalDateTime, end: LocalDateTime) = exception(
        eventId,
        original,
        ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
        },
    )

    /** Cancels the occurrence of repeating [eventId] that was due at [original]. */
    fun cancelOccurrence(eventId: Long, original: LocalDateTime) = exception(
        eventId,
        original,
        ContentValues().apply { put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED) },
    )

    private fun exception(eventId: Long, original: LocalDateTime, values: ContentValues) {
        values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, original.atZone(zone).toInstant().toEpochMilli())
        resolver.insert(Uri.withAppendedPath(CalendarContract.Events.CONTENT_EXCEPTION_URI, eventId.toString()), values)
            ?: throw AssertionError("The calendar refused the exception")
    }

    private fun insert(values: ContentValues): Long {
        values.put(CalendarContract.Events.CALENDAR_ID, id)
        return ContentUris.parseId(resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: throw AssertionError("The calendar refused the event"))
    }

    /** Every live event (not the instances of repeating ones), by start. */
    fun events(): List<Row> {
        val projection = arrayOf(
            CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.UID_2445, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
        )
        val rows = mutableListOf<Row>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(id.toString()),
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                rows += Row(
                    id = c.getLong(0),
                    title = c.getString(1) ?: "",
                    uid = c.getString(2),
                    start = Instant.ofEpochMilli(c.getLong(3)),
                    end = Instant.ofEpochMilli(if (c.isNull(4)) c.getLong(3) else c.getLong(4)),
                    location = c.getString(5) ?: "",
                    description = c.getString(6) ?: "",
                )
            }
        }
        return rows
    }

    /** The events the Planner wrote. */
    fun appEvents(): List<Row> = events().filter { CalendarSync.isPlannerUid(it.uid) }

    fun delete() {
        testApp.contentResolver.delete(asSyncAdapter(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)), null, null)
    }

    companion object {
        const val ACCOUNT = "planner-test@local"
        const val NAME = "Planner test"
        const val COLOUR = 0xFF3F7CAC

        private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()

        /** Creates the calendar; the calendar permissions must already be granted. */
        fun create(zone: ZoneId = appContainer.clock.zone()): TestCalendar {
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, NAME)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, NAME)
                put(CalendarContract.Calendars.CALENDAR_COLOR, COLOUR.toInt())
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, zone.id)
            }
            val uri = testApp.contentResolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), values)
                ?: throw AssertionError("The emulator's calendar provider refused a local calendar")
            return TestCalendar(ContentUris.parseId(uri), zone)
        }
    }
}
