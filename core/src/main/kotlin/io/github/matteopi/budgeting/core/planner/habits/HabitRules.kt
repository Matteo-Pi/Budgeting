package io.github.matteopi.budgeting.core.planner.habits

import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit

/** The fields of the habit editor that can carry an error. */
enum class HabitField { NAME, TARGET, DAYS, CATEGORY, MIN_MINUTES, PAUSE }

/** What a habit needs before it can be saved (docs/PLAN.md §13.4). The editor keeps *Save* off until there are none. */
object HabitRules {

    /** A week of minutes: the largest target that can be met. */
    const val MAX_TARGET = 7 * 24 * 60
    const val MAX_TIMES_PER_WEEK = 50

    fun errors(habit: Habit): Map<HabitField, String> {
        val errors = LinkedHashMap<HabitField, String>()
        val goal = habit.goal
        if (habit.name.isBlank()) errors[HabitField.NAME] = "Enter a name."
        when (goal.kind) {
            GoalKind.ON_DAYS -> if (goal.days.isEmpty()) errors[HabitField.DAYS] = "Choose at least one day."
            GoalKind.TIMES_PER_WEEK ->
                if (goal.target !in 1..MAX_TIMES_PER_WEEK) errors[HabitField.TARGET] = "Choose 1 to $MAX_TIMES_PER_WEEK times a week."
            GoalKind.AT_LEAST, GoalKind.AT_MOST -> {
                val most = if (goal.unit == GoalUnit.MINUTES && goal.period == GoalPeriod.DAY) 24 * 60 else MAX_TARGET
                if (goal.target !in 1..most) errors[HabitField.TARGET] = "Enter an amount from 1 to $most."
            }
        }
        if (habit.countsRecord && habit.categoryId == null) errors[HabitField.CATEGORY] = "Choose the category the record counts."
        habit.minMinutes?.let {
            if (it !in 1..24 * 60) errors[HabitField.MIN_MINUTES] = "A session is 1 minute to 24 hours."
        }
        val from = habit.pausedFrom
        val until = habit.pausedUntil
        when {
            until != null && from == null -> errors[HabitField.PAUSE] = "Choose when the pause starts."
            from != null && until != null && until.isBefore(from) -> errors[HabitField.PAUSE] = "The pause ends before it starts."
        }
        return errors
    }

    fun isValid(habit: Habit): Boolean = errors(habit).isEmpty()
}
