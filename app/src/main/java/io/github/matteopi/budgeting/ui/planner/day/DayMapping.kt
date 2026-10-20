package io.github.matteopi.budgeting.ui.planner.day

import androidx.compose.runtime.Immutable
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.day.DayLayer
import io.github.matteopi.budgeting.core.planner.day.DaySegment
import io.github.matteopi.budgeting.core.planner.day.DaySource
import io.github.matteopi.budgeting.core.planner.day.DayTimeline
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.RecordOrigin
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.domain.planner.DayModel
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableMapping
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How a stretch of the day stands: the state chip on Today and the style on the day canvas. */
enum class ChunkState { DONE, TO_CONFIRM, PLANNED, FREE, NOT_RECORDED }

/**
 * One stretch of the day on screen (docs/PLAN.md §13.9). [startMinute] and [endMinute] count from the window's start
 * (a 25-hour day has 1,500 minutes). [key] finds the core segment again.
 */
@Immutable
data class SegmentUi(
    val key: String,
    val state: ChunkState,
    val title: String,
    val detail: String,
    val timeText: String,
    val colorArgb: Long?,
    val start: Instant,
    val end: Instant,
    val startMinute: Int,
    val endMinute: Int,
    val recordId: Long? = null,
    val planId: Long? = null,
    /** In review: a stretch to fill in or confirm. */
    val needsReview: Boolean = false,
    /** "Study, EC101, 14:00 to 15:30, done". */
    val description: String,
)

/** A boundary that can be dragged: the end of [leftId] and/or the start of [rightId], in one [layer]. */
@Immutable
data class BoundaryUi(val key: String, val at: Instant, val minute: Int, val layer: DayLayer, val leftId: Long?, val rightId: Long?, val label: String)

/** An hour line on the canvas, at [minute] from the window's start. */
@Immutable
data class HourUi(val minute: Int, val label: String)

/** What was planned, in the thin strip beside the past hours. */
@Immutable
data class StripUi(val startMinute: Int, val endMinute: Int, val colorArgb: Long?)

/** The day editor's and Today's pure mapping (docs/PLAN.md §13.9), tested on the JVM. */
object DayMapping {

    fun minuteOf(window: Interval, at: Instant): Int = Duration.between(window.start, at).toMinutes().toInt()

    /** The instant [minute] minutes into the window. */
    fun instantAt(window: Interval, minute: Int): Instant = window.start.plus(minute.toLong(), ChronoUnit.MINUTES)

    private fun clock(at: Instant, zone: ZoneId) = PlannerText.time(at.atZone(zone).toLocalTime())

    fun range(start: Instant, end: Instant, zone: ZoneId) = "${clock(start, zone)}–${clock(end, zone)}"

    private fun label(category: TimeCategory?, moduleId: Long?, modules: Map<Long, Module>): String =
        listOfNotNull(category?.name ?: "Something", moduleId?.let { modules[it]?.code }).joinToString(" · ")

    /** The title, detail and colour of what a source expected. */
    fun describe(source: DaySource, categories: Map<Long, TimeCategory>, modules: Map<Long, Module>): Triple<String, String, Long?> = when (source) {
        is DaySource.Plan -> Triple(
            label(categories[source.block.categoryId], source.block.moduleId, modules),
            source.block.note.ifBlank { "Planned" },
            categories[source.block.categoryId]?.colorArgb,
        )
        is DaySource.Class -> Triple(
            source.occurrence.title,
            listOf(TimetableMapping.kindLabel(source.occurrence.kind), source.occurrence.room, source.occurrence.location).filter { it.isNotBlank() }
                .joinToString(" · "),
            source.occurrence.colorArgb ?: source.occurrence.moduleId?.let { modules[it]?.colorArgb },
        )
        is DaySource.Event -> Triple(source.instance.title.ifBlank { "(No title)" }, source.instance.location.ifBlank { "Calendar" }, source.instance.colorArgb)
    }

    fun segments(model: DayModel, modules: List<Module>, review: Boolean = false): List<SegmentUi> {
        val categories = model.categoriesById
        val byModule = modules.associateBy { it.id }
        val zone = model.zone
        return model.segments.map { s ->
            val time = range(s.interval.start, s.interval.end, zone)
            val (state, title, detail, colour) = when (s) {
                is DaySegment.Recorded -> {
                    val c = categories[s.chunk.categoryId]
                    val how = when (s.chunk.origin) {
                        RecordOrigin.TIMER -> "Timer"
                        RecordOrigin.CONFIRMED -> "As planned"
                        RecordOrigin.MANUAL -> ""
                    }
                    Quad(ChunkState.DONE, label(c, s.chunk.moduleId, byModule), s.chunk.note.ifBlank { how }, c?.colorArgb)
                }
                is DaySegment.Waiting -> describe(s.source, categories, byModule).let { (t, d, c) -> Quad(ChunkState.TO_CONFIRM, t, d, c) }
                is DaySegment.Planned -> describe(s.source, categories, byModule).let { (t, d, c) -> Quad(ChunkState.PLANNED, t, d, c) }
                is DaySegment.Unrecorded -> Quad(ChunkState.NOT_RECORDED, "Not recorded", "", null)
                is DaySegment.Free -> Quad(ChunkState.FREE, "Free", "", null)
            }
            val needsReview = review && (
                s is DaySegment.Waiting || (s is DaySegment.Unrecorded && s.interval.minutes >= DayTimeline.MIN_UNRECORDED_MINUTES)
                )
            SegmentUi(
                key = keyOf(s),
                state = state,
                title = title,
                detail = detail,
                timeText = time,
                colorArgb = colour,
                start = s.interval.start,
                end = s.interval.end,
                startMinute = minuteOf(model.window, s.interval.start),
                endMinute = minuteOf(model.window, s.interval.end),
                recordId = (s as? DaySegment.Recorded)?.chunk?.id,
                planId = ((s as? DaySegment.Planned)?.source as? DaySource.Plan)?.block?.id
                    ?: ((s as? DaySegment.Waiting)?.source as? DaySource.Plan)?.block?.id,
                needsReview = needsReview,
                description = listOf(title, detail, "${clock(s.interval.start, zone)} to ${clock(s.interval.end, zone)}", stateLabel(state))
                    .filter { it.isNotBlank() }.joinToString(", "),
            )
        }
    }

    private data class Quad(val state: ChunkState, val title: String, val detail: String, val colour: Long?)

    /** A stable key per segment: its kind, what it shows, and where it starts. */
    fun keyOf(s: DaySegment): String = when (s) {
        is DaySegment.Recorded -> "record:${s.chunk.id}"
        is DaySegment.Waiting -> "waiting:${s.source.key}:${s.interval.start.toEpochMilli()}"
        is DaySegment.Planned -> "planned:${s.source.key}:${s.interval.start.toEpochMilli()}"
        is DaySegment.Unrecorded -> "unrecorded:${s.interval.start.toEpochMilli()}"
        is DaySegment.Free -> "free:${s.interval.start.toEpochMilli()}"
    }

    fun stateLabel(state: ChunkState): String = when (state) {
        ChunkState.DONE -> "Done"
        ChunkState.TO_CONFIRM -> "To confirm"
        ChunkState.PLANNED -> "Planned"
        ChunkState.FREE -> "Free"
        ChunkState.NOT_RECORDED -> "Not recorded"
    }

    /**
     * The draggable boundaries: each record's edges and each future plan block's, a shared edge once. A record's
     * edges are those inside the window; plans only from now on.
     */
    fun boundaries(model: DayModel): List<BoundaryUi> {
        val result = ArrayList<BoundaryUi>()
        fun add(layer: DayLayer, spans: List<Triple<Long, Instant, Instant>>) {
            val sorted = spans.sortedBy { it.second }
            sorted.forEachIndexed { i, (id, start, end) ->
                val previous = sorted.getOrNull(i - 1)
                if (previous == null || previous.third != start) result += boundary(model, layer, start, null, id)
                val next = sorted.getOrNull(i + 1)
                result += if (next != null && next.second == end) boundary(model, layer, end, id, next.first) else boundary(model, layer, end, id, null)
            }
        }
        add(DayLayer.RECORD, model.records.map { Triple(it.id, it.start, it.end) })
        add(DayLayer.PLAN, model.plans.filter { it.end.isAfter(model.now) && it.outcomeOverride == null }.map { Triple(it.id, it.start, it.end) })
        return result.filter { it.at in model.window || it.at == model.window.end }
    }

    private fun boundary(model: DayModel, layer: DayLayer, at: Instant, leftId: Long?, rightId: Long?) = BoundaryUi(
        key = "${layer.name}:${leftId ?: "-"}:${rightId ?: "-"}",
        at = at,
        minute = minuteOf(model.window, at),
        layer = layer,
        leftId = leftId,
        rightId = rightId,
        label = clock(at, model.zone),
    )

    /** An hour line for each whole local hour in the window ("08:00"), in local time across a clock change. */
    fun hours(window: Interval, zone: ZoneId): List<HourUi> {
        val result = ArrayList<HourUi>()
        var at = window.start.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()
        if (at.isBefore(window.start)) at = at.plus(1, ChronoUnit.HOURS)
        while (!at.isAfter(window.end)) {
            result += HourUi(minuteOf(window, at), clock(at, zone))
            at = at.plus(1, ChronoUnit.HOURS)
        }
        return result
    }

    /** What was planned in the past hours (plan blocks and classes), for the strip beside the record. */
    fun plannedStrip(model: DayModel, modules: List<Module>): List<StripUi> {
        val byModule = modules.associateBy { it.id }
        val past = Interval(model.window.start, minOf(model.now, model.window.end))
        if (past.isEmpty) return emptyList()
        val plans = model.plans.mapNotNull { p ->
            Interval(p.start, p.end).intersect(past)?.let { StripUi(minuteOf(model.window, it.start), minuteOf(model.window, it.end), model.categoriesById[p.categoryId]?.colorArgb) }
        }
        val classes = model.occurrences.filter { !it.cancelled }.mapNotNull { o ->
            Interval(o.start, o.end).intersect(past)?.let {
                StripUi(minuteOf(model.window, it.start), minuteOf(model.window, it.end), o.colorArgb ?: o.moduleId?.let { m -> byModule[m]?.colorArgb })
            }
        }
        return (plans + classes).sortedBy { it.startMinute }
    }

    /** "3 not recorded · 2 to confirm", or null when there is nothing to do. */
    fun reviewBanner(model: DayModel): String? {
        val (unrecorded, waiting) = DayTimeline.counts(model.segments)
        if (unrecorded == 0 && waiting == 0) return null
        return listOfNotNull(
            if (unrecorded > 0) "$unrecorded not recorded" else null,
            if (waiting > 0) "$waiting to confirm" else null,
        ).joinToString(" · ")
    }
}
