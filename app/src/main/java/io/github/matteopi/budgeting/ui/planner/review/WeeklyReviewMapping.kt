package io.github.matteopi.budgeting.ui.planner.review

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.insights.Period
import io.github.matteopi.budgeting.core.planner.insights.WeeklyReview
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.ui.planner.insights.DeadlineRowUi
import io.github.matteopi.budgeting.ui.planner.insights.PlannerInsightsMapping
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionRowUi
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionsMapping
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableMapping
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Immutable
data class ReviewLineUi(val name: String, val text: String, val colorArgb: Long? = null)

/** A day of the planned week and its suggestions. */
@Immutable
data class ReviewDayUi(val date: LocalDate, val title: String, val rows: List<SuggestionRowUi>)

/** Another time *Move* offers: "Tue 27 Oct · 14:00–15:30". */
@Immutable
data class MoveOptionUi(val interval: Interval, val text: String)

/** The weekly review screen (docs/PLAN.md §12.10, §13.9). */
@Immutable
data class WeeklyReviewUi(
    val reviewedTitle: String,
    /** "Next week", or "This week" when the review is early in the week and plans the rest of it. */
    val planningLabel: String,
    val planningTitle: String,
    val studyText: String,
    val outcomesText: String?,
    val top: List<ReviewLineUi>,
    val habits: List<ReviewLineUi>,
    val deadlines: List<DeadlineRowUi>,
    val days: List<ReviewDayUi>,
) {
    val suggestionCount: Int get() = days.sumOf { it.rows.size }
}

/** The weekly review's pure mapping, tested on the JVM. */
object WeeklyReviewMapping {

    fun ui(review: WeeklyReview, categories: List<TimeCategory>, today: LocalDate, zone: ZoneId): WeeklyReviewUi {
        val byId = categories.associateBy { it.id }
        val outcomes = review.outcomes
        return WeeklyReviewUi(
            reviewedTitle = title(review.reviewed),
            planningLabel = if (today in review.planning) "This week" else "Next week",
            planningTitle = title(review.planning),
            studyText = "Planned ${PlannerText.duration(review.plannedMinutes.toInt())} · studied ${PlannerText.duration(review.studiedMinutes.toInt())}",
            outcomesText = if (outcomes.total == 0) null else
                "${outcomes.done} of ${outcomes.total} planned ${if (outcomes.total == 1) "block" else "blocks"} done" +
                    (if (outcomes.partly > 0) ", ${outcomes.partly} partly" else "") + (if (outcomes.skipped > 0) ", ${outcomes.skipped} skipped" else ""),
            top = review.topCategories.mapNotNull { t ->
                val c = byId[t.categoryId] ?: return@mapNotNull null
                ReviewLineUi(c.name, PlannerText.duration(t.minutes.toInt()), c.colorArgb)
            },
            habits = review.habits.map { h ->
                val rate = h.rate?.let { "${(it * 100).roundToInt()}% this week" } ?: "Nothing to judge"
                val streak = if (h.habit.streaks && h.currentStreak > 0) " · streak ${h.currentStreak}" else ""
                ReviewLineUi(h.habit.name, rate + streak)
            },
            deadlines = review.deadlines.map { PlannerInsightsMapping.deadlineRow(it, today) },
            days = review.nextWeek.map { (date, list) ->
                ReviewDayUi(date, dayTitle(date, today), list.map { SuggestionsMapping.row(it, today, zone) })
            },
        )
    }

    /** "19–25 Oct". */
    fun title(period: Period): String = TimetableMapping.weekTitle(period.from)

    /** "Monday 26 Oct", "Today", "Tomorrow". */
    fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${PlannerText.date(date, today).substringAfter(' ')}"
    }

    fun moveOption(interval: Interval, today: LocalDate, zone: ZoneId): MoveOptionUi {
        val start = interval.start.atZone(zone)
        val end = interval.end.atZone(zone)
        return MoveOptionUi(interval, "${PlannerText.date(start.toLocalDate(), today)} · ${PlannerText.range(start.toLocalTime(), end.toLocalTime())}")
    }
}
