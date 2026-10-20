package io.github.matteopi.budgeting.core.planner.calendar

import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.Task
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** An event the app wants in the chosen calendar (docs/PLAN.md §13.4). [key] says what it shows; [uid] is its stable UID. */
data class DesiredEvent(
    val key: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val location: String = "",
    val description: String = CalendarSync.DESCRIPTION,
) {
    val uid: String get() = CalendarSync.uidOf(key)
    val contentHash: String get() = CalendarSync.hash(title, start, end, location, description)
}

/** An event the app wrote, as the calendar has it now: found by its link or by its Planner UID. */
data class ExistingEvent(
    val eventId: Long,
    val uid: String?,
    val title: String,
    val start: Instant,
    val end: Instant,
    val location: String,
    val description: String,
) {
    val contentHash: String get() = CalendarSync.hash(title, start, end, location, description)
}

/**
 * What a sync does: events to [inserts], to [updates] (by event id), to [deletes], links to point at an existing
 * event ([relinks], after a restore or on a new phone) and links to drop.
 */
data class SyncPlan(
    val inserts: List<DesiredEvent> = emptyList(),
    val updates: List<Pair<Long, DesiredEvent>> = emptyList(),
    val deletes: List<Long> = emptyList(),
    val relinks: List<Pair<DesiredEvent, Long>> = emptyList(),
    val dropLinks: List<Long> = emptyList(),
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty() && relinks.isEmpty() && dropLinks.isEmpty()
}

/**
 * Writing the Planner into a calendar (docs/PLAN.md §12.5, §13.4). Pure: the app reads the links and the events, applies
 * the plan and records the links. Nothing that isn't the app's (no link and no Planner UID) is ever touched, and events
 * in the past are never deleted, so the calendar keeps its history.
 */
object CalendarSync {

    const val DESCRIPTION = "Added by the Budgeting app's Planner"
    const val UID_PREFIX = "planner-"
    const val UID_SUFFIX = "@budgeting"

    /** A task with a time but no length shows for this long. */
    val DEFAULT_TASK_LENGTH: Duration = Duration.ofMinutes(30)

    /** "task:4" → "planner-task-4@budgeting"; "entry:12:2026-10-20" → "planner-entry-12-2026-10-20@budgeting". */
    fun uidOf(key: String): String = UID_PREFIX + key.replace(':', '-') + UID_SUFFIX

    fun isPlannerUid(uid: String?): Boolean = uid != null && uid.startsWith(UID_PREFIX) && uid.endsWith(UID_SUFFIX)

    fun hash(title: String, start: Instant, end: Instant, location: String, description: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(listOf(title, start.toEpochMilli(), end.toEpochMilli(), location, description).joinToString("\u0001").toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Open tasks with a date and a time, due from [now] on, as events of their length (or [DEFAULT_TASK_LENGTH]). */
    fun taskEvents(tasks: List<Task>, now: Instant, zone: ZoneId): List<DesiredEvent> = tasks.mapNotNull { task ->
        val date = task.dueDate ?: return@mapNotNull null
        val time: LocalTime = task.dueTime ?: return@mapNotNull null
        if (!task.isOpen) return@mapNotNull null
        val start = ZonedDateTime.of(date, time, zone).toInstant()
        val end = start.plus(task.lengthMinutes?.let { Duration.ofMinutes(it.toLong()) } ?: DEFAULT_TASK_LENGTH)
        if (!end.isAfter(now)) return@mapNotNull null
        DesiredEvent("task:${task.id}", task.title, start, end, task.location ?: "")
    }

    /** Timetable classes ending after [now], as events ("Room 2.04, Campus"). */
    fun occurrenceEvents(occurrences: List<Occurrence>, now: Instant): List<DesiredEvent> =
        occurrences.filter { it.end.isAfter(now) }.map { o ->
            DesiredEvent(o.key, o.title, o.start, o.end, listOf(o.room, o.location).filter { it.isNotBlank() }.joinToString(", "))
        }

    fun diff(desired: List<DesiredEvent>, links: List<CalendarLink>, existing: List<ExistingEvent>, now: Instant): SyncPlan {
        val existingById = existing.associateBy { it.eventId }
        val existingByUid = existing.filter { it.uid != null }.associateBy { it.uid!! }
        val linksByKey = links.associateBy { it.itemKey }
        val inserts = mutableListOf<DesiredEvent>()
        val updates = mutableListOf<Pair<Long, DesiredEvent>>()
        val relinks = mutableListOf<Pair<DesiredEvent, Long>>()
        val claimed = mutableSetOf<Long>()

        for (event in desired.distinctBy { it.key }) {
            val link = linksByKey[event.key]
            val found = link?.let { existingById[it.eventId] } ?: existingByUid[event.uid]
            if (found == null || found.eventId in claimed) {
                inserts += event
                continue
            }
            claimed += found.eventId
            if (link == null || link.eventId != found.eventId) relinks += event to found.eventId
            if (found.contentHash != event.contentHash) updates += found.eventId to event
        }

        val desiredKeys = desired.map { it.key }.toSet()
        val deletes = mutableListOf<Long>()
        val dropLinks = mutableListOf<Long>()
        for (link in links) {
            if (link.itemKey in desiredKeys && link.eventId in claimed) continue
            if (link.itemKey in desiredKeys) {
                // Linked, but the event is gone or another one was found: the link is replaced.
                dropLinks += link.id
                continue
            }
            val event = existingById[link.eventId]
            if (event != null && event.end.isAfter(now) && event.eventId !in claimed) deletes += event.eventId
            dropLinks += link.id
        }
        // Events with a Planner UID that nothing wants any more (an item deleted, then a restore): future ones go.
        for (event in existing) {
            if (event.eventId in claimed || event.eventId in deletes) continue
            if (isPlannerUid(event.uid) && event.end.isAfter(now)) deletes += event.eventId
        }
        return SyncPlan(inserts, updates, deletes.distinct(), relinks, dropLinks.distinct())
    }
}
