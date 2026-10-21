package io.github.matteopi.budgeting.core.planner.habits

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.Intervals
import io.github.matteopi.budgeting.core.planner.model.DayReview
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * How a habit's day went.
 * - [DONE] and [MISSED]: judged. A day of a weekly goal is [DONE] when something counted that day.
 * - [SKIPPED] ("Skip today"), [REST] (not a chosen day), [PAUSED] (paused, or outside term for a term-time habit) and
 *   [NOT_REVIEWED] (a limit counted from the record, on a day whose review isn't finished) are neutral.
 * - [PENDING]: today or later, still to do (or, for a limit, still to be judged).
 * - [NONE]: a day of a weekly goal with nothing counted, which the week judges.
 */
enum class HabitDayState { DONE, MISSED, SKIPPED, REST, PAUSED, PENDING, NOT_REVIEWED, NONE }

/** How a week of a weekly goal went. [NEUTRAL]: nothing to judge (its target rounds to 0, or a limit not reviewed). */
enum class HabitWeekState { DONE, MISSED, PENDING, NEUTRAL }

/** One day: its [state] and [amount], which is the larger of what was entered by hand ([byHand]) and [recorded]. */
data class HabitDay(
    val date: LocalDate,
    val state: HabitDayState,
    val amount: Int,
    val byHand: Int = 0,
    val recorded: Int = 0,
)

/** One week (Monday first) of a weekly goal: the [amount] of its active days against its [target]. */
data class HabitWeek(val start: LocalDate, val amount: Int, val target: Int, val state: HabitWeekState)

/** The current day's or week's [amount] against its [target]; [over] when a limit is exceeded. */
data class HabitPeriodProgress(val amount: Int, val target: Int, val period: GoalPeriod, val over: Boolean)

/**
 * A habit as of today: [days] and [weeks] (weekly goals only) within the range asked for, this period's [current]
 * progress, and the streaks (in days for daily goals, in weeks for weekly ones), counted from the habit's start.
 */
data class HabitStatus(
    val habit: Habit,
    val today: LocalDate,
    val days: List<HabitDay>,
    val weeks: List<HabitWeek>,
    val current: HabitPeriodProgress,
    val currentStreak: Int,
    val bestStreak: Int,
) {
    fun day(date: LocalDate): HabitDay? = days.firstOrNull { it.date == date }

    fun week(start: LocalDate): HabitWeek? = weeks.firstOrNull { it.start == start }
}

/** Habits' days, weeks, progress and streaks, worked out from the entries and the record (docs/PLAN.md §13.4). */
object HabitProgress {

    /** The Monday of [date]'s week. */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Evaluates [habit] for [from] to [to] as of [today].
     *
     * - [entries] are the habit's own (others are ignored); [records] any record chunks (only [Habit.categoryId]'s
     *   count), which must cover the habit's start to [to] for the streaks to be right.
     * - [reviews] say which days are reviewed; [terms] which are in term; [window] is each date's record window.
     * - Days before the habit's start are left out.
     */
    fun evaluate(
        habit: Habit,
        entries: List<HabitEntry>,
        records: List<RecordChunk>,
        reviews: List<DayReview>,
        terms: List<TermPeriod>,
        window: (LocalDate) -> Interval,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
    ): HabitStatus {
        val goal = habit.goal
        val reviewed = reviews.filter { it.finishedAt != null }.map { it.date }.toSet()
        val byDate = entries.filter { it.habitId == habit.id }.associateBy { it.date }
        // Days are worked out from the start to the end of the last week shown (or today's), so weeks are whole.
        val horizon = weekStart(maxOf(to, today)).plusDays(6)
        val counter = RecordCounter(habit, records)

        val infos = ArrayList<DayInfo>()
        var date = habit.startDate
        while (!date.isAfter(horizon)) {
            val entry = byDate[date]
            val skipped = entry?.skipped == true
            val byHand = if (habit.countsByHand && !skipped) (entry?.amount ?: 0).coerceAtLeast(0) else 0
            val recorded = if (habit.countsRecord && !skipped) counter.amount(window(date)) else 0
            infos += DayInfo(
                date = date,
                paused = isPaused(habit, date, terms),
                skipped = skipped,
                scheduled = goal.weekly || goal.days.isEmpty() || date.dayOfWeek in goal.days,
                byHand = byHand,
                recorded = recorded,
                reviewed = date in reviewed,
            )
            date = date.plusDays(1)
        }

        return if (goal.weekly) weekly(habit, infos, from, to, today) else daily(habit, infos, from, to, today)
    }

    private data class DayInfo(
        val date: LocalDate,
        val paused: Boolean,
        val skipped: Boolean,
        val scheduled: Boolean,
        val byHand: Int,
        val recorded: Int,
        val reviewed: Boolean,
    ) {
        val amount: Int get() = maxOf(byHand, recorded)
        val active: Boolean get() = !paused && !skipped
    }

    private fun isPaused(habit: Habit, date: LocalDate, terms: List<TermPeriod>): Boolean {
        val from = habit.pausedFrom
        if (from != null && !date.isBefore(from) && (habit.pausedUntil == null || !date.isAfter(habit.pausedUntil))) return true
        return habit.termOnly && terms.none { !date.isBefore(it.startDate) && !date.isAfter(it.endDate) }
    }

    private fun daily(habit: Habit, infos: List<DayInfo>, from: LocalDate, to: LocalDate, today: LocalDate): HabitStatus {
        val goal = habit.goal
        val target = goal.effectiveTarget
        val days = infos.map { d ->
            val state = when {
                d.paused -> HabitDayState.PAUSED
                !d.scheduled -> HabitDayState.REST
                d.skipped -> HabitDayState.SKIPPED
                goal.kind == GoalKind.AT_MOST -> when {
                    !d.date.isBefore(today) -> HabitDayState.PENDING
                    habit.countsRecord && !d.reviewed -> HabitDayState.NOT_REVIEWED
                    d.amount <= target -> HabitDayState.DONE
                    else -> HabitDayState.MISSED
                }
                d.amount >= target -> HabitDayState.DONE
                !d.date.isBefore(today) -> HabitDayState.PENDING
                else -> HabitDayState.MISSED
            }
            HabitDay(d.date, state, d.amount, d.byHand, d.recorded)
        }
        val (current, best) = streaks(days.filter { !it.date.isAfter(today) }.map { it.state.toJudgement() })
        val todayAmount = infos.firstOrNull { it.date == today }?.amount ?: 0
        return HabitStatus(
            habit = habit,
            today = today,
            days = days.filter { !it.date.isBefore(from) && !it.date.isAfter(to) },
            weeks = emptyList(),
            current = HabitPeriodProgress(todayAmount, target, GoalPeriod.DAY, goal.kind == GoalKind.AT_MOST && todayAmount > target),
            currentStreak = current,
            bestStreak = best,
        )
    }

    private fun weekly(habit: Habit, infos: List<DayInfo>, from: LocalDate, to: LocalDate, today: LocalDate): HabitStatus {
        val goal = habit.goal
        val limit = goal.kind == GoalKind.AT_MOST
        val weeks = infos.groupBy { weekStart(it.date) }.toSortedMap().map { (start, week) ->
            val active = week.filter { it.active }
            val target = weeklyTarget(goal.effectiveTarget, active.size)
            val amount = active.sumOf { it.amount }
            val ended = start.plusDays(6).isBefore(today)
            val state = when {
                target == 0 -> HabitWeekState.NEUTRAL
                limit -> when {
                    !ended -> HabitWeekState.PENDING
                    amount > target -> HabitWeekState.MISSED
                    habit.countsRecord && active.any { !it.reviewed } -> HabitWeekState.NEUTRAL
                    else -> HabitWeekState.DONE
                }
                amount >= target -> HabitWeekState.DONE
                !ended -> HabitWeekState.PENDING
                else -> HabitWeekState.MISSED
            }
            HabitWeek(start, amount, target, state)
        }
        val weekStates = weeks.associateBy { it.start }
        val days = infos.map { d ->
            val state = when {
                d.paused -> HabitDayState.PAUSED
                d.skipped -> HabitDayState.SKIPPED
                limit -> when {
                    !d.date.isBefore(today) -> HabitDayState.PENDING
                    habit.countsRecord && !d.reviewed -> HabitDayState.NOT_REVIEWED
                    else -> HabitDayState.NONE
                }
                d.amount > 0 -> HabitDayState.DONE
                d.date.isBefore(today) -> HabitDayState.NONE
                weekStates.getValue(weekStart(d.date)).state == HabitWeekState.DONE -> HabitDayState.NONE
                else -> HabitDayState.PENDING
            }
            HabitDay(d.date, state, d.amount, d.byHand, d.recorded)
        }
        val thisWeek = weekStart(today)
        val (current, best) = streaks(weeks.filter { !it.start.isAfter(thisWeek) }.map { it.state.toJudgement() })
        val now = weekStates[thisWeek]
        return HabitStatus(
            habit = habit,
            today = today,
            days = days.filter { !it.date.isBefore(from) && !it.date.isAfter(to) },
            weeks = weeks.filter { !it.start.plusDays(6).isBefore(from) && !it.start.isAfter(to) },
            current = HabitPeriodProgress(
                amount = now?.amount ?: 0,
                target = now?.target ?: goal.effectiveTarget,
                period = GoalPeriod.WEEK,
                over = limit && now != null && now.amount > now.target,
            ),
            currentStreak = current,
            bestStreak = best,
        )
    }

    /**
     * What a tick (or a reminder's *Done*) enters for a day: 1 for a habit counted in times, the day's target for a daily
     * amount ("read 20 minutes": 20). Null for a weekly amount or a limit, which are entered as amounts instead.
     */
    fun tickAmount(goal: HabitGoal): Int? = when (goal.kind) {
        GoalKind.ON_DAYS, GoalKind.TIMES_PER_WEEK -> 1
        GoalKind.AT_LEAST -> if (goal.period == GoalPeriod.DAY) goal.target else null
        GoalKind.AT_MOST -> null
    }

    /** A weekly target scaled to the days that count: round(target × active days ÷ 7). */
    fun weeklyTarget(target: Int, activeDays: Int): Int =
        if (activeDays >= 7) target else (target * activeDays / 7.0).roundToInt()

    private enum class Judgement { SUCCESS, FAILURE, NEUTRAL }

    private fun HabitDayState.toJudgement() = when (this) {
        HabitDayState.DONE -> Judgement.SUCCESS
        HabitDayState.MISSED -> Judgement.FAILURE
        else -> Judgement.NEUTRAL
    }

    private fun HabitWeekState.toJudgement() = when (this) {
        HabitWeekState.DONE -> Judgement.SUCCESS
        HabitWeekState.MISSED -> Judgement.FAILURE
        else -> Judgement.NEUTRAL
    }

    /** (current, best): successes in a row, neutral ones skipped, a failure starting again. */
    private fun streaks(judgements: List<Judgement>): Pair<Int, Int> {
        var run = 0
        var best = 0
        for (j in judgements) {
            when (j) {
                Judgement.SUCCESS -> {
                    run++
                    best = maxOf(best, run)
                }
                Judgement.FAILURE -> run = 0
                Judgement.NEUTRAL -> Unit
            }
        }
        return run to best
    }

    /**
     * The share of judged days (daily goals) or weeks (weekly goals, by their start) from [from] to [to] that
     * succeeded, or null when none was judged. Only what [status] covers counts.
     */
    fun rate(status: HabitStatus, from: LocalDate, to: LocalDate): Double? {
        val judged = if (status.habit.goal.weekly) {
            status.weeks.filter { !it.start.isBefore(from) && !it.start.isAfter(to) }.map { it.state.toJudgement() }
        } else {
            status.days.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.map { it.state.toJudgement() }
        }.filter { it != Judgement.NEUTRAL }
        if (judged.isEmpty()) return null
        return judged.count { it == Judgement.SUCCESS }.toDouble() / judged.size
    }

    /**
     * The record's part of a day's amount: within the day's window, the chunks of the habit's category (touching ones
     * joined, so a session split in two counts once) at least [Habit.minMinutes] long. Their number, or their minutes
     * when the goal counts minutes.
     */
    private class RecordCounter(private val habit: Habit, records: List<RecordChunk>) {
        private val sessions: List<Interval> =
            if (habit.categoryId == null) emptyList()
            else Intervals.merge(records.filter { it.categoryId == habit.categoryId }.map { Interval(it.start, it.end) })

        fun amount(window: Interval): Int {
            val pieces = sessions.mapNotNull { it.intersect(window) }.filter { it.minutes >= (habit.minMinutes ?: 1) }
            return if (habit.goal.unit == GoalUnit.MINUTES) pieces.sumOf { it.minutes }.toInt() else pieces.size
        }
    }
}
