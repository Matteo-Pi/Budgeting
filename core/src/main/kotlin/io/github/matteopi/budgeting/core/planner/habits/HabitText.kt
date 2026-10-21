package io.github.matteopi.budgeting.core.planner.habits

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** How habits read on screen and in reminders (docs/PLAN.md §12.9), in one place so they are tested once. */
object HabitText {

    /** "20 m", "1 h"; "once", "3 times"; or a plain count. */
    fun amount(value: Int, unit: GoalUnit): String = when (unit) {
        GoalUnit.MINUTES -> PlannerText.duration(value)
        GoalUnit.TIMES -> if (value == 1) "once" else "$value times"
        GoalUnit.COUNT -> "$value"
    }

    /** "Every day", "On Mon, Wed, Fri", "3 times a week", "20 m a day", "At most 1 h a day", "At least 2 h a week". */
    fun goal(goal: HabitGoal): String = when (goal.kind) {
        GoalKind.ON_DAYS -> if (goal.days.size == 7) "Every day" else "On ${days(goal.days)}"
        GoalKind.TIMES_PER_WEEK -> if (goal.target == 1) "Once a week" else "${goal.target} times a week"
        GoalKind.AT_LEAST, GoalKind.AT_MOST -> {
            val per = if (goal.period == GoalPeriod.WEEK) "a week" else "a day"
            val on = if (goal.period == GoalPeriod.DAY && goal.days.isNotEmpty() && goal.days.size < 7) " on ${days(goal.days)}" else ""
            val text = "${amount(goal.target, goal.unit)} $per$on"
            if (goal.kind == GoalKind.AT_MOST) "At most $text" else text.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * This period's progress: "2 of 3 this week", "15 m of 20 m today", "Done today", "1 h 15 m of 1 h today · over
     * the limit", or why today doesn't count ("Rest day", "Paused", "Skipped today").
     */
    fun progress(status: HabitStatus): String {
        val goal = status.habit.goal
        val current = status.current
        fun of(span: String) = when (goal.unit) {
            GoalUnit.MINUTES -> "${PlannerText.duration(current.amount)} of ${PlannerText.duration(current.target)} $span"
            else -> "${current.amount} of ${current.target} $span"
        }
        val over = if (current.over) " · over the limit" else ""
        if (goal.weekly) return of("this week") + over
        return when (status.day(status.today)?.state) {
            HabitDayState.REST -> "Rest day"
            HabitDayState.PAUSED -> "Paused"
            HabitDayState.SKIPPED -> "Skipped today"
            null -> "Not started yet"
            else -> when (goal.kind) {
                GoalKind.ON_DAYS -> if (current.amount >= 1) "Done today" else "Not done yet today"
                else -> of("today") + over
            }
        }
    }

    /** "Streak 7 days · best 9", "Streak 2 weeks · best 2"; null when the habit's streaks are off. */
    fun streak(status: HabitStatus): String? {
        if (!status.habit.streaks) return null
        val unit = if (status.habit.goal.weekly) "week" else "day"
        val n = status.currentStreak
        return "Streak $n ${if (n == 1) unit else unit + "s"} · best ${status.bestStreak}"
    }

    private fun days(days: Set<DayOfWeek>): String =
        days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
}
