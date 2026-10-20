package io.github.matteopi.budgeting.data.settings

import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplates
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The Planner's reminder settings (docs/PLAN.md §13.6), stored as one JSON value. Later stages add the end-of-day
 * and weekly reviews here; a field missing from an older value takes its default.
 */
@Serializable
data class PlannerReminderSettings(
    /** When tasks without a time of their own remind, in minutes after midnight (default 09:00). */
    val taskMinute: Int = 9 * 60,
) {
    val taskTime: LocalTime get() = LocalTime.of((taskMinute / 60).coerceIn(0, 23), (taskMinute % 60).coerceIn(0, 59))
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
            ?.takeIf { it.taskMinute in 0 until 24 * 60 }
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
}
