package io.github.matteopi.budgeting.ui.planner.habits

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.habits.HabitDayState
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.habits.HabitStatus
import io.github.matteopi.budgeting.core.planner.habits.HabitText
import io.github.matteopi.budgeting.core.planner.habits.HabitWeekState
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.domain.planner.HabitsModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** One day of a habit's week strip: its weekday letter and how it went. */
@Immutable
data class HabitDotUi(val date: LocalDate, val letter: String, val state: HabitDayState?, val isToday: Boolean)

/** A habit on the Habits tab or Today (docs/PLAN.md §12.9): built by [HabitsMapping], drawn by `HabitRow`. */
@Immutable
data class HabitRowUi(
    val id: Long,
    val name: String,
    val colorArgb: Long,
    val goal: String,
    /** "2 of 3 this week", "Done today", "Rest day". */
    val progress: String,
    val streak: String?,
    /** A tick box: for habits filled in by hand that a tick completes. */
    val tickable: Boolean,
    val ticked: Boolean,
    /** *Add*: for amounts and limits filled in by hand. */
    val addsAmount: Boolean,
    val unit: GoalUnit,
    /** Today counts for the habit (not a rest day, paused or before its start), so it can be skipped. */
    val activeToday: Boolean,
    val skipped: Boolean,
    val over: Boolean,
    val week: List<HabitDotUi>,
    /** "Gym, 3 times a week, 1 of 3 this week, Streak 2 weeks · best 2", for accessibility. */
    val description: String,
)

/** The Habits tab: the habits that count today, then the others (rest days, paused, not started), then archived ones. */
data class HabitsSections(
    val today: List<HabitRowUi> = emptyList(),
    val other: List<HabitRowUi> = emptyList(),
    val archived: List<HabitRowUi> = emptyList(),
)

/** One cell of the habit page's month: blank before the 1st and after the last day. */
@Immutable
data class HabitCellUi(
    val date: LocalDate?,
    val label: String,
    val state: HabitDayState?,
    val amount: String,
    val isToday: Boolean,
    /** "Tue 20 Oct, Done, 25 m", for accessibility. */
    val description: String,
)

@Immutable
data class HabitMonthUi(val month: YearMonth, val title: String, val weeks: List<List<HabitCellUi>>)

/** The Habits tab's and the habit page's pure mapping (docs/PLAN.md §13.9), tested on the JVM. */
object HabitsMapping {

    private val NEUTRAL_TODAY = setOf(HabitDayState.REST, HabitDayState.PAUSED)

    fun sections(model: HabitsModel): HabitsSections {
        val rows = model.statuses.sortedBy { it.habit.sortOrder }.map { it to row(it) }
        return HabitsSections(
            today = rows.filter { (s, r) -> !s.habit.archived && r.activeToday }.map { it.second },
            other = rows.filter { (s, r) -> !s.habit.archived && !r.activeToday }.map { it.second },
            archived = rows.filter { (s, _) -> s.habit.archived }.map { it.second },
        )
    }

    /** The habits that count today, for Today's list. */
    fun forToday(model: HabitsModel): List<HabitRowUi> = sections(model).today

    fun row(status: HabitStatus): HabitRowUi {
        val habit = status.habit
        val today = status.day(status.today)
        val tick = HabitProgress.tickAmount(habit.goal)
        val progress = HabitText.progress(status)
        val streak = HabitText.streak(status)
        return HabitRowUi(
            id = habit.id,
            name = habit.name,
            colorArgb = habit.colorArgb,
            goal = HabitText.goal(habit.goal),
            progress = progress,
            streak = streak,
            tickable = habit.countsByHand && tick != null,
            ticked = tick != null && (today?.amount ?: 0) >= tick,
            addsAmount = habit.countsByHand && tick == null,
            unit = habit.goal.unit,
            activeToday = today != null && today.state !in NEUTRAL_TODAY,
            skipped = today?.state == HabitDayState.SKIPPED,
            over = status.current.over,
            week = week(status),
            description = listOfNotNull(habit.name, HabitText.goal(habit.goal), progress, streak).joinToString(", "),
        )
    }

    /** Monday to Sunday of the current week. */
    fun week(status: HabitStatus): List<HabitDotUi> {
        val monday = HabitProgress.weekStart(status.today)
        return (0L..6L).map { i ->
            val date = monday.plusDays(i)
            HabitDotUi(date, date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), status.day(date)?.state, date == status.today)
        }
    }

    fun stateLabel(state: HabitDayState?): String = when (state) {
        HabitDayState.DONE -> "Done"
        HabitDayState.MISSED -> "Missed"
        HabitDayState.SKIPPED -> "Skipped"
        HabitDayState.REST -> "Rest day"
        HabitDayState.PAUSED -> "Paused"
        HabitDayState.PENDING -> "To do"
        HabitDayState.NOT_REVIEWED -> "Not reviewed"
        HabitDayState.NONE -> "Nothing"
        null -> "Not started"
    }

    // ---- The habit page ----

    /** [month] as weeks of seven cells, Monday first. */
    fun month(status: HabitStatus, month: YearMonth): HabitMonthUi {
        val first = month.atDay(1)
        val cells = ArrayList<HabitCellUi>()
        repeat(first.dayOfWeek.value - 1) { cells += blank() }
        for (d in 1..month.lengthOfMonth()) {
            val date = month.atDay(d)
            val day = status.day(date)
            val amount = if (day != null && day.amount > 0 && status.habit.goal.unit == GoalUnit.MINUTES) PlannerText.duration(day.amount)
            else if (day != null && day.amount > 1) "${day.amount}" else ""
            cells += HabitCellUi(
                date = date,
                label = "$d",
                state = day?.state,
                amount = amount,
                isToday = date == status.today,
                description = listOf(PlannerText.date(date, status.today), stateLabel(day?.state), amount).filter { it.isNotEmpty() }.joinToString(", "),
            )
        }
        while (cells.size % 7 != 0) cells += blank()
        return HabitMonthUi(month, "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}", cells.chunked(7))
    }

    private fun blank() = HabitCellUi(null, "", null, "", false, "")

    /** "This month 80%", "Last month 75%": the share of judged days (or weeks) that went well; "–" when none were. */
    fun rates(status: HabitStatus): List<Pair<String, String>> {
        val month = YearMonth.from(status.today)
        val last = month.minusMonths(1)
        fun pct(from: LocalDate, to: LocalDate) = HabitProgress.rate(status, from, to)?.let { "${(it * 100).roundToInt()}%" } ?: "–"
        return listOf(
            "This month" to pct(month.atDay(1), month.atEndOfMonth()),
            "Last month" to pct(last.atDay(1), last.atEndOfMonth()),
            "Since the start" to pct(status.habit.startDate, status.today),
        )
    }

    /** A weekly goal's last weeks, newest first: "Week of 12 Oct · 3 of 3 · Done". */
    fun recentWeeks(status: HabitStatus, count: Int = 6): List<String> {
        if (!status.habit.goal.weekly) return emptyList()
        val unit = status.habit.goal.unit
        fun amount(n: Int) = if (unit == GoalUnit.MINUTES) PlannerText.duration(n) else "$n"
        return status.weeks.filter { !it.start.isAfter(status.today) }.sortedByDescending { it.start }.take(count).map { week ->
            val state = when (week.state) {
                HabitWeekState.DONE -> "Done"
                HabitWeekState.MISSED -> "Missed"
                HabitWeekState.PENDING -> "This week"
                HabitWeekState.NEUTRAL -> "Doesn't count"
            }
            "Week of ${PlannerText.date(week.start, status.today)} · ${amount(week.amount)} of ${amount(week.target)} · $state"
        }
    }

    /** How the habit is filled in, its reminder, term time and pause, one line each. */
    fun details(habit: Habit, categories: List<TimeCategory>, today: LocalDate): List<String> {
        val category = categories.firstOrNull { it.id == habit.categoryId }?.name ?: "its category"
        val session = habit.minMinutes?.let { " (sessions of ${PlannerText.duration(it)} or more)" } ?: ""
        val counting = when (habit.counting) {
            HabitCounting.MANUAL -> "Filled in by hand"
            HabitCounting.RECORD -> "Counted from $category in the day record$session"
            HabitCounting.BOTH -> "By hand and from $category in the day record$session; the larger counts"
        }
        val pause = habit.pausedFrom?.let { from ->
            val until = habit.pausedUntil
            when {
                until != null && until.isBefore(today) -> null
                until != null -> "Paused ${PlannerText.date(from, today)} to ${PlannerText.date(until, today)}"
                else -> "Paused from ${PlannerText.date(from, today)}"
            }
        }
        return listOfNotNull(
            counting,
            habit.reminderTime?.let { "Reminder at ${PlannerText.time(it)}" },
            "Term time only".takeIf { habit.termOnly },
            "Started ${PlannerText.date(habit.startDate, today)}",
            pause,
            "Streaks are off".takeIf { !habit.streaks },
        )
    }

    /** Paused today (by its pause, not by term time). */
    fun pausedNow(habit: Habit, today: LocalDate): Boolean {
        val from = habit.pausedFrom ?: return false
        val until = habit.pausedUntil
        return !today.isBefore(from) && (until == null || !today.isAfter(until))
    }
}
