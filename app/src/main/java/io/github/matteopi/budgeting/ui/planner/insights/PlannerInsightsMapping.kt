package io.github.matteopi.budgeting.ui.planner.insights

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.insights.BlockLength
import io.github.matteopi.budgeting.core.planner.insights.DayPart
import io.github.matteopi.budgeting.core.planner.insights.DeadlineInsight
import io.github.matteopi.budgeting.core.planner.insights.FocusMean
import io.github.matteopi.budgeting.core.planner.insights.HabitInsight
import io.github.matteopi.budgeting.core.planner.insights.OutcomeCounts
import io.github.matteopi.budgeting.core.planner.insights.Period
import io.github.matteopi.budgeting.core.planner.insights.PlanStats
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.domain.planner.InsightsModel
import io.github.matteopi.budgeting.domain.planner.InsightsRange
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableMapping
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One coloured part of a day's bar: a category, or everything else ([colorArgb] null). */
@Immutable
data class BarPartUi(val name: String, val colorArgb: Long?, val minutes: Long)

/** One day's bar: [label] under it ("Mon", "19"), its parts in a fixed order, and what it says when tapped. */
@Immutable
data class DayBarUi(val date: LocalDate, val label: String, val parts: List<BarPartUi>, val description: String) {
    val total: Long get() = parts.sumOf { it.minutes }
}

/** A category's time in the period: "14 h", and "↑ 3 h" against the period before. */
@Immutable
data class TimeRowUi(val categoryId: Long, val name: String, val colorArgb: Long, val minutesText: String, val changeText: String, val up: Boolean?)

/** A module's study against its target: "3 h of 5 h", and how full its bar is (null without a target). */
@Immutable
data class ModuleRowUi(val label: String, val text: String, val fraction: Float?)

/** A labelled mean focus: "08:00–10:00", 4.3 over 8 ratings; [fraction] is the mean out of 5. */
@Immutable
data class FocusRowUi(val label: String, val text: String, val fraction: Float)

@Immutable
data class HabitInsightRowUi(val name: String, val rateText: String, val streakText: String, val missedText: String?)

@Immutable
data class DeadlineRowUi(val title: String, val text: String, val atRisk: Boolean, val riskText: String?)

@Immutable
data class TipUi(val text: String, val numbers: String)

/** The Insights tab (docs/PLAN.md §12.10, §13.9): each card's rows, worked out from [InsightsModel]. */
@Immutable
data class PlannerInsightsUi(
    val title: String,
    val totalText: String,
    val days: List<DayBarUi>,
    val time: List<TimeRowUi>,
    val modules: List<ModuleRowUi>,
    val plannedText: String?,
    val plannedDetail: List<String>,
    val focus: List<FocusRowUi>,
    val focusByLength: List<FocusRowUi>,
    val habits: List<HabitInsightRowUi>,
    val deadlines: List<DeadlineRowUi>,
    val tips: List<TipUi>,
) {
    val hasRecord: Boolean get() = time.isNotEmpty()
}

/** The Insights tab's pure mapping, tested on the JVM. */
object PlannerInsightsMapping {
    /** The bars name this many categories; the rest are *Everything else*. */
    const val BAR_CATEGORIES = 4

    fun ui(model: InsightsModel): PlannerInsightsUi {
        val categories = model.categories.associateBy { it.id }
        return PlannerInsightsUi(
            title = title(model.range, model.period),
            totalText = "${PlannerText.duration(model.time.totalMinutes.toInt())} recorded",
            days = bars(model, categories),
            time = model.time.categories.filter { it.minutes > 0 || it.previousMinutes > 0 }.mapNotNull { t ->
                val category = categories[t.categoryId] ?: return@mapNotNull null
                TimeRowUi(t.categoryId, category.name, category.colorArgb, PlannerText.duration(t.minutes.toInt()), change(t.change, t.previousMinutes), up(t.change))
            },
            modules = model.modules.map { m ->
                val target = m.targetMinutes
                ModuleRowUi(
                    label = m.module.code,
                    text = if (target == null) PlannerText.duration(m.minutes.toInt()) else "${PlannerText.duration(m.minutes.toInt())} of ${PlannerText.duration(target.toInt())}",
                    fraction = target?.let { (m.minutes.toFloat() / it).coerceIn(0f, 1f) },
                )
            },
            plannedText = planned(model.plans),
            plannedDetail = plannedDetail(model.plans),
            focus = model.focus.byHour.toSortedMap().map { (hour, mean) -> focusRow(hours(hour), mean) },
            focusByLength = BlockLength.entries.mapNotNull { length -> model.focus.byLength[length]?.let { focusRow(lengthLabel(length), it) } },
            habits = model.habits.map(::habitRow),
            deadlines = model.deadlines.map { deadlineRow(it, model.today) },
            tips = model.tips.map { TipUi(it.text, it.numbers) },
        )
    }

    /** "19–25 Oct" for a week, "October 2026" for a month. */
    fun title(range: InsightsRange, period: Period): String = when (range) {
        InsightsRange.WEEK -> TimetableMapping.weekTitle(period.from)
        InsightsRange.MONTH -> "${period.from.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${period.from.year}"
    }

    /**
     * Each day's bar: the period's [BAR_CATEGORIES] categories with the most time, in the categories' own order (so
     * the parts sit in the same place every day), then everything else.
     */
    fun bars(model: InsightsModel, categories: Map<Long, TimeCategory>): List<DayBarUi> {
        val named = model.time.categories.filter { it.minutes > 0 && it.categoryId in categories }.take(BAR_CATEGORIES)
            .map { categories.getValue(it.categoryId) }
            .sortedWith(compareBy<TimeCategory> { it.sortOrder }.thenBy { it.id })
        val ids = named.map { it.id }.toSet()
        return model.time.days.map { day ->
            val parts = named.map { BarPartUi(it.name, it.colorArgb, day.minutes[it.id] ?: 0) } +
                BarPartUi("Everything else", null, day.minutes.filterKeys { it !in ids }.values.sum())
            val label = when (model.range) {
                InsightsRange.WEEK -> day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                InsightsRange.MONTH -> day.date.dayOfMonth.toString()
            }
            val shown = parts.filter { it.minutes > 0 }
            val description = PlannerText.date(day.date, model.today) + ": " +
                if (shown.isEmpty()) "nothing recorded" else shown.joinToString(", ") { "${it.name} ${PlannerText.duration(it.minutes.toInt())}" }
            DayBarUi(day.date, label, parts, description)
        }
    }

    /** "↑ 3 h", "↓ 1 h 20 m", "as before", or "new" when there was none before. */
    fun change(change: Long, previous: Long): String = when {
        previous == 0L && change > 0 -> "new"
        change == 0L -> "as before"
        change > 0 -> "↑ ${PlannerText.duration(change.toInt())}"
        else -> "↓ ${PlannerText.duration(abs(change).toInt())}"
    }

    private fun up(change: Long): Boolean? = if (change == 0L) null else change > 0

    /** "7 of 10 planned blocks done (70%)". */
    fun planned(stats: PlanStats): String? {
        val all = stats.all
        if (all.total == 0) return null
        return "${all.done} of ${all.total} planned ${if (all.total == 1) "block" else "blocks"} done (${percent(all.doneRate!!)})"
    }

    /** The partly done and skipped, then where blocks are skipped most: the time of day, weekday and length. */
    fun plannedDetail(stats: PlanStats): List<String> {
        if (stats.all.total == 0) return emptyList()
        val lines = ArrayList<String>()
        lines += "${stats.all.partly} partly · ${stats.all.skipped} skipped"
        worst(stats.byPart.mapKeys { partLabel(it.key) })?.let { lines += it }
        worst(stats.byWeekday.mapKeys { weekdayLabel(it.key) })?.let { lines += it }
        worst(stats.byLength.mapKeys { "Blocks of ${lengthLabel(it.key)}" })?.let { lines += it }
        return lines
    }

    /** The group skipped most often, with 2 blocks or more and at least one skipped: "Evening blocks: 3 of 4 skipped". */
    private fun worst(groups: Map<String, OutcomeCounts>): String? = groups.entries
        .filter { it.value.total >= 2 && it.value.skipped > 0 }
        .sortedWith(compareByDescending<Map.Entry<String, OutcomeCounts>> { it.value.skipRate }.thenByDescending { it.value.total }.thenBy { it.key })
        .firstOrNull()
        ?.let { "${it.key}: ${it.value.skipped} of ${it.value.total} skipped" }

    private fun focusRow(label: String, mean: FocusMean) = FocusRowUi(
        label = label,
        text = "${one(mean.mean)} · ${mean.samples} ${if (mean.samples == 1) "rating" else "ratings"}",
        fraction = (mean.mean / 5.0).toFloat().coerceIn(0f, 1f),
    )

    fun habitRow(h: HabitInsight): HabitInsightRowUi {
        val unit = if (h.habit.goal.weekly) "week" else "day"
        fun count(n: Int) = "$n $unit${if (n == 1) "" else "s"}"
        return HabitInsightRowUi(
            name = h.habit.name,
            rateText = h.rate?.let { "${percent(it)} over 4 weeks" } ?: "Nothing to judge yet",
            streakText = if (h.habit.streaks) "Streak ${count(h.currentStreak)} · best ${h.bestStreak}" else "",
            missedText = h.mostMissed?.let { "Most often missed on ${weekdayLabel(it)}s (${h.missedThatDay} times)" },
        )
    }

    fun deadlineRow(d: DeadlineInsight, today: LocalDate): DeadlineRowUi {
        val due = PlannerText.due(d.task.dueDate!!, d.task.dueTime, today)
        val work = d.leftMinutes?.let { "${PlannerText.duration(d.doneMinutes)} done · ${PlannerText.duration(it)} left" } ?: "${PlannerText.duration(d.doneMinutes)} done · no estimate"
        return DeadlineRowUi(
            title = d.task.title,
            text = "$work · $due",
            atRisk = d.atRisk,
            riskText = if (d.atRisk) "At risk: ${PlannerText.duration(d.pacePerDay.roundToInt())} a day over the last week won't finish it" else null,
        )
    }

    private fun partLabel(part: DayPart) = when (part) {
        DayPart.MORNING -> "Morning blocks"
        DayPart.AFTERNOON -> "Afternoon blocks"
        DayPart.EVENING -> "Evening blocks"
    }

    private fun weekdayLabel(day: DayOfWeek) = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun lengthLabel(length: BlockLength) = when (length) {
        BlockLength.SHORT -> "45 m or less"
        BlockLength.MEDIUM -> "46 to 75 m"
        BlockLength.LONG -> "over 75 m"
    }

    private fun hours(first: Int): String =
        PlannerText.range(LocalTime.of(first, 0), if (first + 2 >= 24) LocalTime.MIDNIGHT else LocalTime.of(first + 2, 0))

    private fun one(value: Double): String = (Math.round(value * 10) / 10.0).toString()

    private fun percent(share: Double): String = "${(share * 100).roundToInt()}%"
}
