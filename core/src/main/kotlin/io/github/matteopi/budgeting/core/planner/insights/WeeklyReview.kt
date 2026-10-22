package io.github.matteopi.budgeting.core.planner.insights

import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.habits.HabitStatus
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A habit in the weekly review: its rate over the reviewed week (null: nothing judged) and its streak. */
data class ReviewHabit(val habit: Habit, val rate: Double?, val currentStreak: Int)

/**
 * The weekly review (docs/PLAN.md §12.10, §13.4): the [reviewed] week's study planned against recorded, how its blocks
 * went, the top 3 categories outside sleep and classes, the habits and the deadlines of the next 14 days; then the
 * [planning] week's suggestions, by day.
 */
data class WeeklyReview(
    val reviewed: Period,
    val planning: Period,
    val plannedMinutes: Long,
    val studiedMinutes: Long,
    val outcomes: OutcomeCounts,
    val topCategories: List<CategoryTime>,
    val habits: List<ReviewHabit>,
    val deadlines: List<DeadlineInsight>,
    val nextWeek: List<Pair<LocalDate, List<Suggestion>>>,
) {
    companion object {
        const val DEADLINE_DAYS = 14L

        /**
         * The week reviewed and the week planned on [today]: up to Wednesday, last week and this one (what is left of it);
         * from Thursday, this week and the next.
         */
        fun weeks(today: LocalDate): Pair<Period, Period> {
            val thisWeek = Period.week(today)
            val reviewed = if (today.dayOfWeek <= DayOfWeek.WEDNESDAY) Period.week(today.minusWeeks(1)) else thisWeek
            return reviewed to Period.week(reviewed.to.plusDays(1))
        }

        /**
         * [plans], [records] and [statuses] must cover the reviewed week (statuses also need its habits' streaks);
         * [suggestions] are the planner's current ones; [studiedByTask] all the time recorded per task.
         */
        fun build(
            today: LocalDate,
            now: Instant,
            zone: ZoneId,
            day: DaySettings,
            records: List<RecordChunk>,
            plans: List<PlanBlock>,
            categories: List<TimeCategory>,
            statuses: List<HabitStatus>,
            tasks: List<Task>,
            studiedByTask: Map<Long, Int>,
            suggestions: List<Suggestion>,
        ): WeeklyReview {
            val (reviewed, planning) = weeks(today)
            val study = categories.filter { it.systemKey == CategoryKey.STUDY }.map { it.id }.toSet()
            val left = categories.filter { it.systemKey == CategoryKey.SLEEP || it.systemKey == CategoryKey.CLASS }.map { it.id }.toSet()
            val window = reviewed.interval(zone)
            val stats = StudyInsights.plans(plans, records, study, reviewed, now, zone)
            val studied = records.filter { it.categoryId in study }.sumOf { Interval(it.start, it.end).intersect(window)?.minutes ?: 0L }
            val time = TimeInsights.summary(records, reviewed, day, zone)
            val deadlines = StudyInsights.deadlines(tasks, studiedByTask, records, today, zone)
                .filter { !it.task.dueDate!!.isAfter(today.plusDays(DEADLINE_DAYS)) }
            val days = planning.dates.filter { !it.isBefore(today) }
            val byDay = suggestions.groupBy { it.interval.start.atZone(zone).toLocalDate() }
            return WeeklyReview(
                reviewed = reviewed,
                planning = planning,
                plannedMinutes = stats.plannedMinutes,
                studiedMinutes = studied,
                outcomes = stats.all,
                topCategories = time.categories.filter { it.categoryId !in left && it.minutes > 0 }.take(3),
                habits = statuses.filter { !it.habit.archived }.map { ReviewHabit(it.habit, HabitProgress.rate(it, reviewed.from, reviewed.to), it.currentStreak) },
                deadlines = deadlines,
                nextWeek = days.map { it to byDay[it].orEmpty().sortedBy { s -> s.interval.start } },
            )
        }
    }
}
