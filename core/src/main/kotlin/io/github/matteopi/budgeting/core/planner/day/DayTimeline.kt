package io.github.matteopi.budgeting.core.planner.day

import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import java.time.Instant

/** Something the day was expected to hold: a plan block, a class or a calendar event. */
sealed interface DaySource {
    val interval: Interval

    /** What a record that confirms it carries: the plan block's id, or this key. */
    val key: String

    data class Plan(val block: PlanBlock) : DaySource {
        override val interval: Interval get() = Interval(block.start, block.end)
        override val key: String get() = "plan:${block.id}"
    }

    data class Class(val occurrence: Occurrence) : DaySource {
        override val interval: Interval get() = Interval(occurrence.start, occurrence.end)
        override val key: String get() = occurrence.key
    }

    data class Event(val instance: CalendarInstance) : DaySource {
        override val interval: Interval get() = Interval(instance.start, instance.end)
        override val key: String get() = "event:${instance.eventId}:${instance.start.toEpochMilli()}"
    }
}

/** One stretch of the day timeline; [interval] is clipped to the day's window. */
sealed interface DaySegment {
    val interval: Interval

    /** What was recorded. */
    data class Recorded(val chunk: RecordChunk, override val interval: Interval) : DaySegment

    /** Past, expected, and not recorded yet: to confirm. */
    data class Waiting(val source: DaySource, override val interval: Interval) : DaySegment

    /** Past, with nothing in it. */
    data class Unrecorded(override val interval: Interval) : DaySegment

    /** Ahead: a plan block, class or event. */
    data class Planned(val source: DaySource, override val interval: Interval) : DaySegment

    /** Ahead, with nothing in it. */
    data class Free(override val interval: Interval) : DaySegment
}

/**
 * The day as one strip (docs/PLAN.md §13.4): records, then what was expected where nothing was recorded (plans over
 * classes over events), then the gaps. Nothing overlaps; everything is clipped to the window and split at [now].
 */
object DayTimeline {

    fun build(
        window: Interval,
        now: Instant,
        records: List<RecordChunk>,
        plans: List<PlanBlock>,
        occurrences: List<Occurrence>,
        instances: List<CalendarInstance>,
        /** Events the app wrote itself (tasks, classes): they already show as the app's own items. */
        appEventIds: Set<Long> = emptySet(),
    ): List<DaySegment> {
        val segments = ArrayList<DaySegment>()
        val taken = ArrayList<Interval>()
        for (r in records.sortedBy { it.start }) {
            val part = Interval(r.start, r.end).intersect(window) ?: continue
            segments += DaySegment.Recorded(r, part)
            taken += part
        }
        // A source a record already confirmed shows no waiting part: the user said what happened.
        val confirmedPlans = records.mapNotNull { it.planBlockId }.toSet()
        val confirmedKeys = records.mapNotNull { it.sourceKey }.toSet()
        val sources: List<DaySource> =
            plans.sortedBy { it.start }.map { DaySource.Plan(it) } +
                occurrences.filter { !it.cancelled }.sortedBy { it.start }.map { DaySource.Class(it) } +
                instances.filter { !it.allDay && it.busy && it.eventId !in appEventIds }.sortedBy { it.start }.map { DaySource.Event(it) }
        for (source in sources) {
            val clipped = source.interval.intersect(window) ?: continue
            val confirmed = when (source) {
                // *Didn't happen* sets an outcome: nothing is left to confirm.
                is DaySource.Plan -> source.block.id in confirmedPlans || source.block.outcomeOverride != null
                else -> source.key in confirmedKeys
            }
            for (part in Intervals.subtract(clipped, taken)) {
                // A confirmed source's past part is left to the gaps below: it shows as not recorded.
                val past = part.intersect(Interval(part.start, now))
                if (past != null && !confirmed) {
                    segments += DaySegment.Waiting(source, past)
                    taken += past
                }
                val ahead = part.intersect(Interval(now, part.end))
                if (ahead != null) {
                    segments += DaySegment.Planned(source, ahead)
                    taken += ahead
                }
            }
        }
        for (gap in Intervals.subtract(window, taken)) {
            gap.intersect(Interval(gap.start, now))?.let { segments += DaySegment.Unrecorded(it) }
            gap.intersect(Interval(now, gap.end))?.let { segments += DaySegment.Free(it) }
        }
        return segments.sortedWith(compareBy<DaySegment> { it.interval.start }.thenBy { it.interval.end })
    }

    /** How many stretches are not recorded and how many wait to be confirmed: the review banner's numbers. */
    fun counts(segments: List<DaySegment>): Pair<Int, Int> =
        segments.count { it is DaySegment.Unrecorded && it.interval.minutes >= MIN_UNRECORDED_MINUTES } to
            segments.count { it is DaySegment.Waiting }

    /** Unrecorded gaps shorter than this are not counted as a stretch to fill in. */
    const val MIN_UNRECORDED_MINUTES = 5
}
