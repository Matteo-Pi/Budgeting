package io.github.matteopi.budgeting.data.settings

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.freetime.FreeSettings
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplates
import java.time.Instant
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The Planner's reminder settings (docs/PLAN.md §13.6), stored as one JSON value. Stage P6 adds the weekly review
 * here; a field missing from an older value takes its default.
 */
@Serializable
data class PlannerReminderSettings(
    /** When tasks without a time of their own remind, in minutes after midnight (default 09:00). */
    val taskMinute: Int = 9 * 60,
    /** The end-of-day review's reminder (default 21:30), when [reviewOn]. */
    val reviewOn: Boolean = true,
    val reviewMinute: Int = 21 * 60 + 30,
) {
    val taskTime: LocalTime get() = timeOf(taskMinute)

    /** The review reminder's time, or null when it is off. */
    val reviewTime: LocalTime? get() = if (reviewOn) timeOf(reviewMinute) else null

    private fun timeOf(minute: Int) = LocalTime.of((minute / 60).coerceIn(0, 23), (minute % 60).coerceIn(0, 59))
}

/** The running timer (docs/PLAN.md §13.7): only its start and what it records, so it survives the app being stopped. */
@Serializable
data class TimerState(
    val startMillis: Long,
    val categoryId: Long,
    val moduleId: Long? = null,
    val taskId: Long? = null,
) {
    val start: Instant get() = Instant.ofEpochMilli(startMillis)
}

/** A calendar the user chose, kept with its account and name so it can be found again if its id changes. */
@Serializable
data class CalendarChoice(val id: Long, val account: String, val name: String)

/**
 * Which phone calendars the Planner shows, and where it writes (docs/PLAN.md §12.5): [writeTo] is null while writing
 * back is off (the default). Device-specific, so not in backups.
 */
@Serializable
data class PlannerCalendarSettings(
    val read: List<CalendarChoice> = emptyList(),
    val writeTo: CalendarChoice? = null,
    /** With writing back on, the timetable's classes go into [writeTo] too. */
    val writeTimetable: Boolean = false,
)

/** Reading and writing the Planner's JSON settings: a value that doesn't parse gives the defaults, never a crash. */
object PlannerSettingsCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val templatesSerializer = ListSerializer(BreakdownTemplate.serializer())

    fun decodeReminders(text: String?): PlannerReminderSettings =
        text?.let { runCatching { json.decodeFromString(PlannerReminderSettings.serializer(), it) }.getOrNull() }
            ?.takeIf { it.taskMinute in 0 until 24 * 60 && it.reviewMinute in 0 until 24 * 60 }
            ?: PlannerReminderSettings()

    fun encodeReminders(settings: PlannerReminderSettings): String = json.encodeToString(PlannerReminderSettings.serializer(), settings)

    /** The user's templates, or the defaults when there are none (or they don't parse). */
    fun decodeTemplates(text: String?): List<BreakdownTemplate> =
        text?.let { runCatching { json.decodeFromString(templatesSerializer, it) }.getOrNull() }
            ?.filter { it.name.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: BreakdownTemplates.defaults

    fun encodeTemplates(templates: List<BreakdownTemplate>): String = json.encodeToString(templatesSerializer, templates)

    fun decodeCalendars(text: String?): PlannerCalendarSettings =
        text?.let { runCatching { json.decodeFromString(PlannerCalendarSettings.serializer(), it) }.getOrNull() } ?: PlannerCalendarSettings()

    fun encodeCalendars(settings: PlannerCalendarSettings): String = json.encodeToString(PlannerCalendarSettings.serializer(), settings)

    /** The user's day; wrong values fall back to the defaults. */
    fun decodeDay(text: String?): DaySettings =
        (text?.let { runCatching { json.decodeFromString(DaySettings.serializer(), it) }.getOrNull() } ?: DaySettings()).sanitized()

    fun encodeDay(settings: DaySettings): String = json.encodeToString(DaySettings.serializer(), settings)

    fun decodeFree(text: String?): FreeSettings =
        text?.let { runCatching { json.decodeFromString(FreeSettings.serializer(), it) }.getOrNull() }
            ?.takeIf { it.defaultBufferMinutes in 0..240 && it.minGapMinutes in 0..240 }
            ?: FreeSettings()

    fun encodeFree(settings: FreeSettings): String = json.encodeToString(FreeSettings.serializer(), settings)

    fun decodeTimer(text: String?): TimerState? = text?.let { runCatching { json.decodeFromString(TimerState.serializer(), it) }.getOrNull() }

    fun encodeTimer(state: TimerState): String = json.encodeToString(TimerState.serializer(), state)
}
