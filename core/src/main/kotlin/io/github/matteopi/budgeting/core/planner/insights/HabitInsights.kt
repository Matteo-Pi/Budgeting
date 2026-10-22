package io.github.matteopi.budgeting.core.planner.insights

import io.github.matteopi.budgeting.core.planner.habits.HabitDayState
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.habits.HabitStatus
import io.github.matteopi.budgeting.core.planner.model.Habit
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A habit over the last [HabitInsights.WEEKS] weeks: its completion [rate] (null: nothing judged), its streaks, and the
 * weekday it is most often missed on with how many times ([mostMissed] is null for weekly goals, or with fewer than
 * [HabitInsights.MIN_MISSES] misses on any one weekday).
 */
data class HabitInsight(
    val habit: Habit,
    val rate: Double?,
    val currentStreak: Int,
    val bestStreak: Int,
    val mostMissed: DayOfWeek?,
    val missedThatDay: Int,
)

/** Habits (docs/PLAN.md §12.10, §13.4): each habit's rate over 4 weeks, its streaks, and the weekday it slips on. */
object HabitInsights {
    const val WEEKS = 4L
    const val MIN_MISSES = 2

    /** [statuses] must cover the 4 weeks to [today]; archived habits are left out. */
    fun of(statuses: List<HabitStatus>, today: LocalDate): List<HabitInsight> {
        val from = today.minusDays(WEEKS * 7 - 1)
        return statuses.filter { !it.habit.archived }.map { status ->
            val misses = if (status.habit.goal.weekly) {
                emptyMap()
            } else {
                status.days.filter { it.state == HabitDayState.MISSED && !it.date.isBefore(from) && !it.date.isAfter(today) }
                    .groupingBy { it.date.dayOfWeek }.eachCount()
            }
            val worst = misses.entries.filter { it.value >= MIN_MISSES }
                .sortedWith(compareByDescending<Map.Entry<DayOfWeek, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()
            HabitInsight(
                habit = status.habit,
                rate = HabitProgress.rate(status, from, today),
                currentStreak = status.currentStreak,
                bestStreak = status.bestStreak,
                mostMissed = worst?.key,
                missedThatDay = worst?.value ?: 0,
            )
        }
    }
}
