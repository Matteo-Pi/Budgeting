package io.github.matteopi.budgeting.domain.planner

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.day.DaySegment
import io.github.matteopi.budgeting.core.planner.day.DayTimeline
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.freetime.FreeDay
import io.github.matteopi.budgeting.core.planner.freetime.FreeSettings
import io.github.matteopi.budgeting.core.planner.freetime.FreeTime
import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import io.github.matteopi.budgeting.core.planner.model.DayReview
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * One day of the Planner (docs/PLAN.md §13.9): its [window], the timeline [segments], what was recorded and planned,
 * and its free time ([free], of which [unplannedMinutes] is not planned yet, [freeLeftMinutes] of that from now).
 */
data class DayModel(
    val date: LocalDate,
    val window: Interval,
    val waking: Interval,
    val now: Instant,
    val zone: ZoneId,
    val segments: List<DaySegment>,
    val records: List<RecordChunk>,
    val plans: List<PlanBlock>,
    val categories: List<TimeCategory>,
    val occurrences: List<Occurrence>,
    val instances: List<CalendarInstance>,
    val free: FreeDay,
    val unplannedMinutes: Long,
    val freeLeftMinutes: Long,
    val review: DayReview?,
    val calendar: CalendarState,
) {
    val finished: Boolean get() = review?.finishedAt != null

    /** The window has started and not ended. */
    val isCurrent: Boolean get() = now in window

    val categoriesById: Map<Long, TimeCategory> get() = categories.associateBy { it.id }
}

/**
 * The Planner's shared day computations (docs/PLAN.md §13.9): records and plans in the day's window, the classes and
 * calendar events around it, free time and the review. Only the current day collects the minute tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerService(
    private val dayRepository: DayRepository,
    private val timetableRepository: TimetableRepository,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val plannerCalendar: PlannerCalendar,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    private data class Timetable3(val entries: List<TimetableEntry>, val exceptions: List<EntryException>, val terms: List<TermPeriod>)

    private data class Stored(
        val records: List<RecordChunk>,
        val plans: List<PlanBlock>,
        val categories: List<TimeCategory>,
        val reviews: List<DayReview>,
        val calendar: CalendarState,
    )

    private val timetable: Flow<Timetable3> = combine(
        timetableRepository.observeEntries(),
        timetableRepository.observeExceptions(),
        planRepository.observeTermPeriods(),
    ) { entries, exceptions, terms -> Timetable3(entries, exceptions, terms) }

    /** The day the current moment belongs to (docs/PLAN.md §13.2), changing at the day's start. */
    val currentDay: Flow<LocalDate> = combine(settingsStore.plannerDay, clock.minuteFlow) { settings, now ->
        DayWindows.currentDay(now, settings, clock.zone())
    }.distinctUntilChanged()

    fun dayFlow(date: LocalDate): Flow<DayModel> = combine(settingsStore.plannerDay, settingsStore.plannerFree) { day, free -> day to free }
        .flatMapLatest { (daySettings, freeSettings) ->
            val zone = clock.zone()
            val window = DayWindows.recordWindow(date, daySettings, zone)
            val waking = DayWindows.wakingWindow(date, daySettings, zone)
            val span = Interval(minOf(window.start, waking.start), maxOf(window.end, waking.end))
            val nowAtStart = Instant.ofEpochMilli(clock.nowMillis())
            // A past or future day doesn't change with the minute.
            val now: Flow<Instant> = if (nowAtStart.isBefore(span.end) && !nowAtStart.isBefore(span.start.minusSeconds(3_600))) {
                clock.minuteFlow
            } else {
                flowOf(nowAtStart)
            }
            val stored = combine(
                dayRepository.observeRecords(span),
                dayRepository.observePlans(span),
                dayRepository.observeCategories(),
                dayRepository.observeReviews(date, date),
                plannerCalendar.observe(span.start, span.end),
            ) { records, plans, categories, reviews, calendar -> Stored(records, plans, categories, reviews, calendar) }
            combine(stored, timetable, taskRepository.observeOpenTasks(), now) { s, t, tasks, at ->
                build(date, freeSettings, window, waking, zone, at, s, t, tasks)
            }
        }

    private fun build(
        date: LocalDate,
        freeSettings: FreeSettings,
        window: Interval,
        waking: Interval,
        zone: ZoneId,
        now: Instant,
        s: Stored,
        t: Timetable3,
        tasks: List<Task>,
    ): DayModel {
        // A fixed day's window can run into the next date, so its classes are looked for on both.
        val occurrences = Timetable.occurrences(t.entries, t.exceptions, t.terms, date.minusDays(1), date.plusDays(1), zone, includeCancelled = true)
            .filter { Interval(it.start, it.end).overlaps(Interval(minOf(window.start, waking.start), maxOf(window.end, waking.end))) }
        val instances = s.calendar.instances
        val segments = DayTimeline.build(window, now, s.records, s.plans, occurrences, instances)
        val free = FreeTime.compute(
            waking,
            FreeTime.busyItems(occurrences, instances, tasks, zone),
            FreeTime.meals(date, freeSettings, zone),
            freeSettings.travel,
            freeSettings.minGapMinutes,
        )
        val left = Interval(maxOf(now, waking.start), waking.end)
        return DayModel(
            date = date,
            window = window,
            waking = waking,
            now = now,
            zone = zone,
            segments = segments,
            records = s.records,
            plans = s.plans,
            categories = s.categories,
            occurrences = occurrences,
            instances = instances,
            free = free,
            unplannedMinutes = FreeTime.unplannedMinutes(free.free, s.plans, waking),
            freeLeftMinutes = if (left.isEmpty) 0 else FreeTime.unplannedMinutes(free.free, s.plans, left),
            review = s.reviews.firstOrNull(),
            calendar = s.calendar,
        )
    }
}
