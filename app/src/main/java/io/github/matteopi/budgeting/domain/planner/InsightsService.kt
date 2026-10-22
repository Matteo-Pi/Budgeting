package io.github.matteopi.budgeting.domain.planner

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.insights.DeadlineInsight
import io.github.matteopi.budgeting.core.planner.insights.EfficiencyRules
import io.github.matteopi.budgeting.core.planner.insights.FocusInsights
import io.github.matteopi.budgeting.core.planner.insights.FocusSummary
import io.github.matteopi.budgeting.core.planner.insights.HabitInsight
import io.github.matteopi.budgeting.core.planner.insights.HabitInsights
import io.github.matteopi.budgeting.core.planner.insights.ModuleStudy
import io.github.matteopi.budgeting.core.planner.insights.Period
import io.github.matteopi.budgeting.core.planner.insights.PlanStats
import io.github.matteopi.budgeting.core.planner.insights.StudyInsights
import io.github.matteopi.budgeting.core.planner.insights.TimeInsights
import io.github.matteopi.budgeting.core.planner.insights.TimeSummary
import io.github.matteopi.budgeting.core.planner.insights.Tip
import io.github.matteopi.budgeting.core.planner.insights.TipInput
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn

/** Whether the Insights tab shows a week or a month. */
enum class InsightsRange { WEEK, MONTH }

/**
 * The Insights tab's numbers for [period] (docs/PLAN.md §12.10): where time went, study against the targets, planned
 * against done, focus, the habits, the deadlines and the tips (the last two as of [today], over the last four weeks).
 */
data class InsightsModel(
    val range: InsightsRange,
    val period: Period,
    val today: LocalDate,
    val categories: List<TimeCategory>,
    val time: TimeSummary,
    val modules: List<ModuleStudy>,
    val plans: PlanStats,
    val focus: FocusSummary,
    val habits: List<HabitInsight>,
    val deadlines: List<DeadlineInsight>,
    val tips: List<Tip>,
)

/**
 * Insights (docs/PLAN.md §13.9) worked out from the day record, the plans, the timetable, the tasks and the habits.
 * Nothing is stored; it reads the shown period, the one before it (for the changes) and the last four weeks (tips and
 * the deadlines' pace).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightsService(
    private val dayRepository: DayRepository,
    private val timetableRepository: TimetableRepository,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val moduleRepository: ModuleRepository,
    private val habitService: HabitService,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    companion object {
        /** The tips look at this many days to today. */
        const val TIP_DAYS = 28L

        fun periodOf(range: InsightsRange, date: LocalDate): Period = when (range) {
            InsightsRange.WEEK -> Period.week(date)
            InsightsRange.MONTH -> Period.month(date)
        }
    }

    private data class Stored(val records: List<RecordChunk>, val plans: List<PlanBlock>, val categories: List<TimeCategory>)

    private data class Work(val tasks: List<Task>, val modules: List<Module>, val studiedByTask: Map<Long, Int>)

    private data class Week(val entries: List<TimetableEntry>, val exceptions: List<EntryException>, val terms: List<TermPeriod>)

    /** The insights of the [range] holding [date]. */
    fun observe(range: InsightsRange, date: LocalDate): Flow<InsightsModel> =
        combine(settingsStore.plannerDay, clock.todayFlow) { day, today -> day to today }.flatMapLatest { (day, today) ->
            val zone = clock.zone()
            val period = periodOf(range, date)
            val tipPeriod = Period(today.minusDays(TIP_DAYS - 1), today)
            val from = minOf(period.previous().from, tipPeriod.from)
            val to = maxOf(period.to, today)
            // A day's window may start the evening before (a cut-off) and ends the next day at the latest.
            val reads = Interval(from.minusDays(1).atStartOfDay(zone).toInstant(), to.plusDays(2).atStartOfDay(zone).toInstant())
            val stored = combine(dayRepository.observeRecords(reads), dayRepository.observePlans(reads), dayRepository.observeCategories()) { r, p, c ->
                Stored(r, p, c)
            }
            val work = combine(taskRepository.observeOpenTasks(), moduleRepository.observeModules(), dayRepository.observeMinutesByTask()) { t, m, s ->
                Work(t, m, s)
            }
            val week = combine(timetableRepository.observeEntries(), timetableRepository.observeExceptions(), planRepository.observeTermPeriods()) { e, x, t ->
                Week(e, x, t)
            }
            combine(stored, work, week, habitService.observe()) { st, wk, tt, habits ->
                build(range, period, tipPeriod, today, day, st, wk, tt, habits)
            }
        }.flowOn(Dispatchers.Default)

    private fun build(
        range: InsightsRange,
        period: Period,
        tipPeriod: Period,
        today: LocalDate,
        day: DaySettings,
        st: Stored,
        wk: Work,
        tt: Week,
        habits: HabitsModel,
    ): InsightsModel {
        val zone = clock.zone()
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val study = st.categories.filter { it.systemKey == CategoryKey.STUDY }.map { it.id }.toSet()
        val occurrences = Timetable.occurrences(tt.entries, tt.exceptions, tt.terms, tipPeriod.from, tipPeriod.to, zone)
        return InsightsModel(
            range = range,
            period = period,
            today = today,
            categories = st.categories,
            time = TimeInsights.summary(st.records, period, day, zone),
            modules = StudyInsights.modules(st.records, wk.modules, study, period, zone),
            plans = StudyInsights.plans(st.plans, st.records, study, period, now, zone),
            focus = FocusInsights.summary(st.records, study, period, zone),
            habits = HabitInsights.of(habits.statuses, habits.today),
            deadlines = StudyInsights.deadlines(wk.tasks, wk.studiedByTask, st.records, today, zone),
            tips = EfficiencyRules.all(TipInput(tipPeriod, st.records, st.plans, occurrences, st.categories, now, zone)),
        )
    }
}
