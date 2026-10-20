package io.github.matteopi.budgeting.domain.planner

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.calendar.CalendarSync
import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.data.calendar.CalendarAccess
import io.github.matteopi.budgeting.data.calendar.CalendarInfo
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.data.settings.CalendarChoice
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the linked calendars give a screen (docs/PLAN.md §13.7). */
sealed interface CalendarState {
    /** No calendars chosen. */
    data object Off : CalendarState

    /** Calendars are chosen, but the permission was refused or taken away. */
    data object NeedsPermission : CalendarState

    data class Ready(val instances: List<CalendarInstance>) : CalendarState
}

/** [instances] when the calendars are ready, else none. */
val CalendarState.instances: List<CalendarInstance> get() = (this as? CalendarState.Ready)?.instances ?: emptyList()

/** Finds [choices] among the phone's calendars by id, else (a new phone, a re-added account) by account and name. */
fun resolveCalendars(choices: List<CalendarChoice>, calendars: List<CalendarInfo>): List<CalendarInfo> =
    choices.mapNotNull { choice ->
        calendars.firstOrNull { it.id == choice.id } ?: calendars.firstOrNull { it.accountName == choice.account && it.name == choice.name }
    }.distinctBy { it.id }

/**
 * The linked calendars, read offline from the phone (docs/PLAN.md §12.5). Screens collect [observe] for the days they
 * show; it re-reads on every calendar change and on [refresh] (a screen coming back, a permission granted), and leaves
 * out the events the app wrote itself, which already show as the app's own items.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerCalendar(
    private val access: CalendarAccess,
    private val settingsStore: SettingsStore,
    private val timetableRepository: TimetableRepository,
    private val clock: Clock,
) {
    private val refreshes = MutableStateFlow(0)

    fun refresh() = refreshes.update { it + 1 }

    fun observe(from: Instant, to: Instant): Flow<CalendarState> =
        combine(settingsStore.plannerCalendars, refreshes, access.changes(), timetableRepository.observeLinkedEventIds()) { settings, _, _, linked ->
            settings to linked
        }.mapLatest { (settings, linked) ->
            when {
                settings.read.isEmpty() -> CalendarState.Off
                !access.canRead() -> CalendarState.NeedsPermission
                else -> {
                    val ids = resolveCalendars(settings.read, access.calendars()).map { it.id }.toSet()
                    CalendarState.Ready(access.instances(from, to, ids, clock.zone()).filter { it.eventId !in linked })
                }
            }
        }.distinctUntilChanged()
}

/**
 * Writing back (docs/PLAN.md §12.5, §13.7): puts timed tasks (and, if asked, the timetable's classes) into the chosen
 * calendar, using [CalendarSync]'s plan. Android sends them to Google the next time the phone is online. One sync at
 * a time; a refusal (no permission, the calendar gone) leaves everything as it was.
 */
class PlannerCalendarSync(
    private val access: CalendarAccess,
    private val settingsStore: SettingsStore,
    private val taskRepository: TaskRepository,
    private val timetableRepository: TimetableRepository,
    private val planRepository: PlanRepository,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** How far ahead classes are written: to the end of the next term, at most this many days. */
    private val maxDaysAhead = 200L

    /** Returns false when it couldn't write (and so changed nothing). */
    suspend fun sync(): Boolean = mutex.withLock {
        val settings = settingsStore.plannerCalendars.first()
        val choice = settings.writeTo ?: return@withLock true
        if (!access.canWrite()) return@withLock false
        val calendar = resolveCalendars(listOf(choice), access.calendars()).firstOrNull()?.takeIf { it.writable }
            ?: return@withLock false
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val zone = clock.zone()
        val today = clock.today()

        val desired = CalendarSync.taskEvents(taskRepository.getOpenTasks(), now, zone) +
            if (settings.writeTimetable) {
                val terms = planRepository.observeTermPeriods().first()
                val occurrences = Timetable.occurrences(
                    timetableRepository.getEntries(), timetableRepository.getExceptions(), terms, today, horizon(today, terms), zone,
                )
                CalendarSync.occurrenceEvents(occurrences, now)
            } else {
                emptyList()
            }

        val links = timetableRepository.getLinks()
        val (here, elsewhere) = links.partition { it.calendarId == calendar.id }
        // Links into another calendar (the user chose a new one): their coming events go, then the links.
        for ((calendarId, group) in elsewhere.groupBy { it.calendarId }) {
            val gone = access.appEvents(calendarId, group.map { it.eventId }).filter { it.end.isAfter(now) }.map { it.eventId }
            access.apply(calendarId, emptyList(), emptyList(), gone, zone)
        }
        if (elsewhere.isNotEmpty()) timetableRepository.updateLinks(emptyList(), elsewhere.map { it.id })

        val plan = CalendarSync.diff(desired, here, access.appEvents(calendar.id, here.map { it.eventId }), now)
        if (plan.isEmpty) return@withLock true
        val inserted = access.apply(calendar.id, plan.inserts, plan.updates, plan.deletes, zone) ?: return@withLock false
        fun link(key: String, uid: String, hash: String, eventId: Long) =
            CalendarLink(itemKey = key, calendarId = calendar.id, eventId = eventId, uid = uid, contentHash = hash)
        val relinked = plan.relinks.map { it.first.key }.toSet()
        val upserts = plan.inserts.mapNotNull { e -> inserted[e.key]?.let { link(e.key, e.uid, e.contentHash, it) } } +
            plan.relinks.map { (e, id) -> link(e.key, e.uid, e.contentHash, id) } +
            plan.updates.filter { (_, e) -> e.key !in relinked }.map { (id, e) -> link(e.key, e.uid, e.contentHash, id) }
        timetableRepository.updateLinks(upserts, plan.dropLinks)
        true
    }

    /** *Remove what the app added*: every event the app wrote, in every calendar it wrote to. Returns how many. */
    suspend fun removeAll(): Int = mutex.withLock {
        val links = timetableRepository.getLinks()
        val calendarIds = links.map { it.calendarId }.toSet() + listOfNotNull(settingsStore.plannerCalendars.first().writeTo?.id)
        var removed = 0
        for (calendarId in calendarIds) {
            val events = access.appEvents(calendarId, links.filter { it.calendarId == calendarId }.map { it.eventId }).map { it.eventId }
            if (events.isNotEmpty() && access.apply(calendarId, emptyList(), emptyList(), events, clock.zone()) != null) removed += events.size
        }
        timetableRepository.clearLinks()
        removed
    }

    /** The end of the term after today's (or of today's, in its last weeks), else 120 days on; never past [maxDaysAhead]. */
    private fun horizon(today: LocalDate, terms: List<TermPeriod>): LocalDate {
        val upcoming = terms.filter { !it.endDate.isBefore(today) }.sortedBy { it.startDate }
        val end = upcoming.getOrNull(1)?.endDate ?: upcoming.firstOrNull()?.endDate ?: today.plusDays(120)
        return minOf(end, today.plusDays(maxDaysAhead))
    }
}
