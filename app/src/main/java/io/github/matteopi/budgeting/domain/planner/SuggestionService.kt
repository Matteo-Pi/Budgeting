package io.github.matteopi.budgeting.domain.planner

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.freetime.FreeSettings
import io.github.matteopi.budgeting.core.planner.freetime.FreeTime
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RevisionTopic
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.suggest.Demands
import io.github.matteopi.budgeting.core.planner.suggest.FocusProfile
import io.github.matteopi.budgeting.core.planner.suggest.StudyContext
import io.github.matteopi.budgeting.core.planner.suggest.StudyInput
import io.github.matteopi.budgeting.core.planner.suggest.StudyPlanner
import io.github.matteopi.budgeting.core.planner.suggest.StudyPrefs
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.SuggestionRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The coming fortnight's suggestions (docs/PLAN.md §12.7), with what accepting one needs and the planner's [input], from
 * which the weekly review's *Move* finds other times.
 */
data class SuggestionsModel(
    val today: LocalDate,
    val suggestions: List<Suggestion>,
    val studyCategoryId: Long?,
    val prefs: StudyPrefs,
    val daysOff: Set<LocalDate>,
    val input: StudyInput? = null,
)

/**
 * Suggested study time (docs/PLAN.md §13.9): 14 days ahead, worked out from the free time (the day windows less
 * classes, busy events, exams, meals and travel), the plans, the record, the deadlines, modules, revision topics and
 * habits. Debounced by 300 ms and on `Dispatchers.Default`; a newer input drops the run in progress. "Now" moves in
 * quarter hours, so the list doesn't change every minute.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SuggestionService(
    private val dayRepository: DayRepository,
    private val timetableRepository: TimetableRepository,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val moduleRepository: ModuleRepository,
    private val suggestionRepository: SuggestionRepository,
    private val plannerCalendar: PlannerCalendar,
    private val habitService: HabitService,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    companion object {
        /** How far back the record and plans are read: this week, and revision's reviews. */
        const val HISTORY_DAYS = 56L
    }

    private data class Settings(val day: DaySettings, val free: FreeSettings, val study: StudyPrefs, val daysOff: Set<LocalDate>)

    private data class Stored(val plans: List<PlanBlock>, val records: List<RecordChunk>, val categories: List<TimeCategory>)

    private data class Plan(
        val tasks: List<Task>,
        val modules: List<Module>,
        val topics: List<RevisionTopic>,
        val dismissed: Set<String>,
        val studiedByTask: Map<Long, Int>,
    )

    private data class Week(val entries: List<TimetableEntry>, val exceptions: List<EntryException>, val terms: List<TermPeriod>)

    private val settings: Flow<Settings> = combine(
        settingsStore.plannerDay, settingsStore.plannerFree, settingsStore.plannerStudy, settingsStore.plannerDaysOff,
    ) { day, free, study, off -> Settings(day, free, study, off) }

    /** The next quarter hour (now, on one), so no suggestion starts in the past. */
    private val quarter: Flow<Instant> = clock.minuteFlow
        .map { it.truncatedTo(ChronoUnit.MINUTES).let { t -> t.plusSeconds((900 - t.epochSecond % 900) % 900) } }
        .distinctUntilChanged()

    fun observe(): Flow<SuggestionsModel> = combine(settings, habitService.observe().map { it.today }.distinctUntilChanged()) { s, today -> s to today }
        .flatMapLatest { (s, today) ->
            val zone = clock.zone()
            val dates = (0 until StudyPlanner.HORIZON_DAYS).map { today.plusDays(it) }
            val windows = dates.map { DayWindows.wakingWindow(it, s.day, zone) }
            val range = Interval(windows.first().start, windows.last().end)
            val history = Interval(range.start.minus(Duration.ofDays(HISTORY_DAYS)), range.end)
            val stored = combine(dayRepository.observePlans(history), dayRepository.observeRecords(history), dayRepository.observeStoredCategories()) { p, r, c ->
                Stored(p, r, c)
            }
            val plan = combine(taskRepository.observeOpenTasks(), moduleRepository.observeModules(), suggestionRepository.observeTopics(),
                suggestionRepository.observeDismissed(), dayRepository.observeMinutesByTask()) { t, m, topics, d, studied -> Plan(t, m, topics, d, studied) }
            val week = combine(timetableRepository.observeEntries(), timetableRepository.observeExceptions(), planRepository.observeTermPeriods()) { e, x, t ->
                Week(e, x, t)
            }
            combine(stored, plan, week, plannerCalendar.observe(range.start, range.end), combine(habitService.observe(), quarter) { h, q -> h to q }) {
                    st, pl, wk, calendar, (habits, now) ->
                val occurrences = Timetable.occurrences(wk.entries, wk.exceptions, wk.terms, today.minusDays(1), dates.last(), zone)
                val busy = FreeTime.busyItems(occurrences, calendar.instances, pl.tasks, zone)
                val free = dates.indices.flatMap { i ->
                    val waking = windows[i]
                    FreeTime.compute(waking, busy.filter { it.interval.overlaps(waking) }, FreeTime.meals(dates[i], s.free, zone), s.free.travel, s.free.minGapMinutes).free
                }
                val study = st.categories.filter { it.systemKey == CategoryKey.STUDY }.map { it.id }.toSet()
                val totals = StudyContext.totals(st.plans, st.records, study, now, today, zone)
                val demands = Demands.from(
                    today = today,
                    prefs = s.study,
                    tasks = pl.tasks,
                    studiedByTask = pl.studiedByTask,
                    plannedByTask = totals.plannedByTask,
                    modules = pl.modules,
                    studiedByModule = totals.studiedByModule,
                    plannedByModule = totals.plannedByModule,
                    topics = pl.topics,
                    topicReviews = totals.topicReviews,
                    habits = habits.statuses,
                    plannedByHabit = totals.plannedByHabit,
                )
                val input = StudyInput(
                    now = now,
                    zone = zone,
                    free = FreeTime.unplanned(free, st.plans),
                    demands = demands,
                    prefs = s.study,
                    dismissed = pl.dismissed,
                    daysOff = s.daysOff,
                    usedByDay = totals.usedByDay,
                    focus = FocusProfile.from(st.records, st.plans, study, now, zone),
                )
                SuggestionsModel(today, StudyPlanner.suggest(input), study.firstOrNull(), s.study, s.daysOff, input)
            }
        }
        .debounce(300)
        .flowOn(Dispatchers.Default)
}
