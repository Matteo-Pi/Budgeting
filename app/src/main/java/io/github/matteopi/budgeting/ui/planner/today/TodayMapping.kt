package io.github.matteopi.budgeting.ui.planner.today

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.day.DaySegment
import io.github.matteopi.budgeting.core.planner.day.DayTimeline
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.domain.planner.DayModel
import io.github.matteopi.budgeting.ui.planner.day.ChunkState
import io.github.matteopi.budgeting.ui.planner.day.DayMapping

/** One row of the day's agenda on Today: a stretch with its time, colour, title and state chip. */
@Immutable
data class AgendaRowUi(
    val key: String,
    val state: ChunkState,
    val timeText: String,
    val title: String,
    val detail: String,
    val colorArgb: Long?,
)

/** Today's pure mapping (docs/PLAN.md §13.9), tested on the JVM. */
object TodayMapping {

    /** Free or unrecorded stretches shorter than this are left out of the agenda. */
    const val MIN_GAP_MINUTES = 15L

    /** The day as an agenda: one row per stretch, short gaps left out. */
    fun agenda(model: DayModel, modules: List<Module>): List<AgendaRowUi> {
        val shown = model.segments.filter { s ->
            (s !is DaySegment.Free && s !is DaySegment.Unrecorded) || s.interval.minutes >= MIN_GAP_MINUTES
        }
        val keys = shown.map { DayMapping.keyOf(it) }.toSet()
        return DayMapping.segments(model, modules).filter { it.key in keys }.map { ui ->
            AgendaRowUi(ui.key, ui.state, ui.timeText, ui.title, ui.detail, ui.colorArgb)
        }
    }

    /** "3 h 20 m free (1 h planned)". */
    fun freeSummary(model: DayModel): String {
        val free = model.free.minutes
        val planned = free - model.unplannedMinutes
        val head = "${PlannerText.duration(free.toInt())} free"
        return if (planned > 0) "$head (${PlannerText.duration(planned.toInt())} planned)" else head
    }

    /**
     * *Finish yesterday* shows while yesterday's review is open and the day has something to review: a record, or
     * something that waits to be confirmed.
     */
    fun offerFinishYesterday(yesterday: DayModel?): Boolean =
        yesterday != null && !yesterday.finished && (yesterday.records.isNotEmpty() || DayTimeline.counts(yesterday.segments).second > 0)
}
