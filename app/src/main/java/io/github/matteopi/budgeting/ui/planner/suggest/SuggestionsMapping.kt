package io.github.matteopi.budgeting.ui.planner.suggest

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import java.time.LocalDate
import java.time.ZoneId

/** A suggested block (docs/PLAN.md §12.7): when, what and why, with *Accept* and *Another time*. */
@Immutable
data class SuggestionRowUi(
    val key: String,
    val date: LocalDate,
    /** "Today", "Tomorrow", "Friday", "Mon 2 Nov". */
    val dayText: String,
    val timeText: String,
    val title: String,
    val reason: String,
    /** "Suggested: EC101 essay, 13:00 to 14:30", for accessibility. */
    val description: String,
)

/** The suggestion rows' pure mapping (docs/PLAN.md §13.9), tested on the JVM. */
object SuggestionsMapping {

    fun row(suggestion: Suggestion, today: LocalDate, zone: ZoneId): SuggestionRowUi {
        val start = suggestion.interval.start.atZone(zone)
        val end = suggestion.interval.end.atZone(zone)
        val title = suggestion.demand.title
        return SuggestionRowUi(
            key = suggestion.key,
            date = start.toLocalDate(),
            dayText = PlannerText.day(start.toLocalDate(), today),
            timeText = PlannerText.range(start.toLocalTime(), end.toLocalTime()),
            title = title,
            reason = suggestion.reason,
            description = "Suggested: $title, ${PlannerText.time(start.toLocalTime())} to ${PlannerText.time(end.toLocalTime())}",
        )
    }

    /** The suggestions starting in [window] (Today's waking day, as on its agenda), in time order. */
    fun within(suggestions: List<Suggestion>, window: Interval, today: LocalDate, zone: ZoneId): List<SuggestionRowUi> =
        suggestions.filter { it.interval.start in window }.sortedBy { it.interval.start }.map { row(it, today, zone) }
}
