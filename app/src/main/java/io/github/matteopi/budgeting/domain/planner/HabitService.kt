package io.github.matteopi.budgeting.domain.planner

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.habits.HabitStatus
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.HabitRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/**
 * Every habit as of [today] (the Planner's current day): its status from its start to the end of the month, or of
 * tomorrow if later, so the Habits tab, the habit page, Today and the reminders all read the same numbers.
 */
data class HabitsModel(
    val today: LocalDate,
    val statuses: List<HabitStatus>,
    val categories: List<TimeCategory>,
) {
    val habits: List<Habit> get() = statuses.map { it.habit }

    fun status(habitId: Long): HabitStatus? = statuses.firstOrNull { it.habit.id == habitId }
}

/**
 * Habits worked out from their entries and the day record (docs/PLAN.md §13.4, §13.9). Nothing here is stored: the
 * statuses are computed from the rows each time, so they never go stale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitService(
    private val habitRepository: HabitRepository,
    private val dayRepository: DayRepository,
    private val planRepository: PlanRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    /** The day the current moment belongs to (docs/PLAN.md §13.2), changing at the day's start. */
    private val currentDay: Flow<LocalDate> = combine(settingsStore.plannerDay, clock.minuteFlow) { settings, now ->
        DayWindows.currentDay(now, settings, clock.zone())
    }.distinctUntilChanged()

    private data class Inputs(val habits: List<Habit>, val daySettings: DaySettings, val today: LocalDate)

    /** The Planner's current day, now: the date a tick from a notification goes on. */
    suspend fun today(): LocalDate = currentDay.first()

    fun observe(): Flow<HabitsModel> =
        combine(habitRepository.observeHabits(), settingsStore.plannerDay, currentDay) { habits, day, today -> Inputs(habits, day, today) }
            .flatMapLatest { (habits, daySettings, today) ->
                val zone = clock.zone()
                val window = { date: LocalDate -> DayWindows.recordWindow(date, daySettings, zone) }
                val to = maxOf(today.plusDays(1), today.withDayOfMonth(today.lengthOfMonth()))
                val horizon = HabitProgress.weekStart(to).plusDays(6)
                val from = habits.minOfOrNull { it.startDate }?.coerceAtMost(today) ?: today
                // Only habits counted from the record need it, and only from the earliest of their starts.
                val recordFrom = habits.filter { it.countsRecord }.minOfOrNull { it.startDate }
                val records: Flow<List<RecordChunk>> =
                    if (recordFrom == null || recordFrom.isAfter(horizon)) flowOf(emptyList())
                    else dayRepository.observeRecords(Interval(window(recordFrom).start, window(horizon).end))
                combine(
                    habitRepository.observeEntries(from, horizon),
                    records,
                    dayRepository.observeReviews(from, horizon),
                    planRepository.observeTermPeriods(),
                    // Not the seeding read: the reminders read habits when the app starts, before the Planner is used.
                    dayRepository.observeStoredCategories(),
                ) { entries, recorded, reviews, terms, categories ->
                    HabitsModel(
                        today = today,
                        statuses = habits.map { HabitProgress.evaluate(it, entries, recorded, reviews, terms, window, it.startDate, to, today) },
                        categories = categories,
                    )
                }
            }
            .flowOn(Dispatchers.Default)
}
