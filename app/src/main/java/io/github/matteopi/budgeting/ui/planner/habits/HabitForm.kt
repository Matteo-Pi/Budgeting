package io.github.matteopi.budgeting.ui.planner.habits

import io.github.matteopi.budgeting.core.planner.habits.HabitField
import io.github.matteopi.budgeting.core.planner.habits.HabitRules
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The habit editor's fields (docs/PLAN.md §12.9). [target] is null while the field is empty; [unit] and [period] apply
 * to amounts and limits, [days] to chosen days (and, optionally, a daily amount or limit). The fields the editor
 * doesn't show (the pause, *Suggest a time*, archived, order) are kept from the habit being edited.
 */
data class HabitForm(
    val id: Long = 0,
    val name: String = "",
    val colorArgb: Long = 0xFF2E8B57,
    val kind: GoalKind = GoalKind.ON_DAYS,
    val target: Int? = null,
    val unit: GoalUnit = GoalUnit.MINUTES,
    val period: GoalPeriod = GoalPeriod.DAY,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val counting: HabitCounting = HabitCounting.MANUAL,
    val categoryId: Long? = null,
    val minMinutes: Int? = null,
    val streaks: Boolean = true,
    val reminderTime: LocalTime? = null,
    val termOnly: Boolean = false,
    val startDate: LocalDate,
    val kept: Habit? = null,
) {
    val isNew: Boolean get() = id == 0L

    /** The target counts times a week, a daily or weekly amount in minutes, or a number. */
    val needsTarget: Boolean get() = kind != GoalKind.ON_DAYS

    /** Chosen days matter: for chosen days, and a daily amount or limit (empty there means every day). */
    val usesDays: Boolean get() = kind == GoalKind.ON_DAYS || (kind != GoalKind.TIMES_PER_WEEK && period == GoalPeriod.DAY)

    /** A shortest session applies when the record counts sessions, not minutes. */
    val usesMinMinutes: Boolean get() = counting != HabitCounting.MANUAL && goal.unit != GoalUnit.MINUTES

    val goal: HabitGoal
        get() = when (kind) {
            GoalKind.ON_DAYS -> HabitGoal(GoalKind.ON_DAYS, 1, GoalUnit.TIMES, GoalPeriod.DAY, days)
            GoalKind.TIMES_PER_WEEK -> HabitGoal(GoalKind.TIMES_PER_WEEK, target ?: 0, GoalUnit.TIMES, GoalPeriod.WEEK)
            else -> HabitGoal(kind, target ?: 0, unit, period, if (period == GoalPeriod.DAY && days.size < 7) days else emptySet())
        }

    fun toHabit(): Habit {
        val base = kept ?: Habit(name = "", goal = goal, startDate = startDate)
        return base.copy(
            id = id,
            name = name.trim(),
            colorArgb = colorArgb,
            goal = goal,
            counting = counting,
            categoryId = if (counting == HabitCounting.MANUAL) null else categoryId,
            minMinutes = if (usesMinMinutes) minMinutes else null,
            streaks = streaks,
            reminderTime = reminderTime,
            termOnly = termOnly,
            startDate = startDate,
        )
    }

    val errors: Map<HabitField, String>
        get() {
            val errors = LinkedHashMap(HabitRules.errors(toHabit()))
            if (needsTarget && target == null) errors[HabitField.TARGET] = "Enter how much."
            if (kind != GoalKind.ON_DAYS && kind != GoalKind.TIMES_PER_WEEK && period == GoalPeriod.DAY && days.isEmpty()) {
                errors[HabitField.DAYS] = "Choose at least one day."
            }
            return errors
        }

    /** Switching kind gives each a sensible start: 3 times a week, 20 minutes a day, at most 1 hour a day. */
    fun withKind(next: GoalKind): HabitForm = when (next) {
        kind -> this
        GoalKind.ON_DAYS -> copy(kind = next)
        GoalKind.TIMES_PER_WEEK -> copy(kind = next, target = 3)
        GoalKind.AT_LEAST -> copy(kind = next, target = 20, unit = GoalUnit.MINUTES, period = GoalPeriod.DAY)
        GoalKind.AT_MOST -> copy(kind = next, target = 60, unit = GoalUnit.MINUTES, period = GoalPeriod.DAY)
    }

    companion object {
        fun new(today: LocalDate, colorArgb: Long) = HabitForm(startDate = today, colorArgb = colorArgb)

        fun of(habit: Habit) = HabitForm(
            id = habit.id,
            name = habit.name,
            colorArgb = habit.colorArgb,
            kind = habit.goal.kind,
            target = habit.goal.target.takeIf { habit.goal.kind != GoalKind.ON_DAYS },
            unit = habit.goal.unit,
            period = habit.goal.period,
            days = habit.goal.days.ifEmpty { DayOfWeek.entries.toSet() },
            counting = habit.counting,
            categoryId = habit.categoryId,
            minMinutes = habit.minMinutes,
            streaks = habit.streaks,
            reminderTime = habit.reminderTime,
            termOnly = habit.termOnly,
            startDate = habit.startDate,
            kept = habit,
        )
    }
}
