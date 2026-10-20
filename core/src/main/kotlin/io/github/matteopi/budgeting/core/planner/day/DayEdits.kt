package io.github.matteopi.budgeting.core.planner.day

import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RecordOrigin
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.model.TimeChunk
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * One user action on one layer of the day (records, or plan blocks), as rows to insert, update ((before, after)
 * pairs) and delete (docs/PLAN.md §13.1). The repository applies it in one transaction.
 */
data class EditPlan<T>(
    val inserts: List<T> = emptyList(),
    val updates: List<Pair<T, T>> = emptyList(),
    val deletes: List<T> = emptyList(),
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty()

    /**
     * Undoes this plan once it is applied (its inserts carrying the ids they were given): the inserted rows go, the
     * deleted ones come back with their ids, and the updated ones go back to how they were.
     */
    fun inverse(): EditPlan<T> = EditPlan(inserts = deletes, updates = updates.map { (before, after) -> after to before }, deletes = inserts)
}

/** Which layer a chunk goes in. */
enum class DayLayer { RECORD, PLAN }

/**
 * The day editor's edits (docs/PLAN.md §13.4), pure. Each works on the chunks of one layer and keeps its invariants:
 * no two chunks overlap, and each is at least [MIN_MINUTES] long (a trimmed leftover shorter than that goes).
 */
object DayEdits {

    /** No chunk is shorter than this. */
    const val MIN_MINUTES = 5L

    /** Moving a boundary leaves each side at least this long. */
    const val MIN_SIDE_MINUTES = Snap.STEP_MINUTES.toLong()

    /** A chunk that starts before [now] is a record; one that starts at or after it is a plan. */
    fun layerOf(start: Instant, now: Instant): DayLayer = if (start.isBefore(now)) DayLayer.RECORD else DayLayer.PLAN

    private fun <T : TimeChunk<T>> span(chunk: T) = Interval(chunk.start, chunk.end)

    private fun minutes(start: Instant, end: Instant) = Duration.between(start, end).toMinutes()

    /** The chunks of [existing] that placing [draft] would cut or replace ("This replaces Social 18:00–18:30"). */
    fun <T : TimeChunk<T>> overlapped(existing: List<T>, draft: T): List<T> =
        existing.filter { it.id != draft.id || draft.id == 0L }.filter { span(it).overlaps(span(draft)) }

    /**
     * Puts [draft] in (a new chunk with id 0, or an existing one with new times or fields): it wins over what it
     * overlaps, which is trimmed, split around it, or deleted when nothing long enough is left. With a [window] the
     * draft is first clipped to it; null when nothing of it is left or it is too short.
     */
    fun <T : TimeChunk<T>> place(existing: List<T>, draft: T, window: Interval? = null): EditPlan<T>? {
        val clipped = if (window == null) span(draft) else span(draft).intersect(window) ?: return null
        if (clipped.minutes < MIN_MINUTES) return null
        val placed = draft.withTimes(clipped.start, clipped.end)
        val inserts = ArrayList<T>()
        val updates = ArrayList<Pair<T, T>>()
        val deletes = ArrayList<T>()
        val old = if (placed.id == 0L) null else existing.firstOrNull { it.id == placed.id }
        if (old == null) inserts += placed.withId(0) else if (old != placed) updates += old to placed
        for (other in overlapped(existing, placed)) {
            val left = Intervals.subtract(span(other), listOf(clipped)).filter { it.minutes >= MIN_MINUTES }
            if (left.isEmpty()) {
                deletes += other
                continue
            }
            val first = other.withTimes(left[0].start, left[0].end)
            if (first != other) updates += other to first
            left.drop(1).forEach { inserts += other.withTimes(it.start, it.end).withId(0) }
        }
        return EditPlan(inserts, updates, deletes)
    }

    /** Splits [chunk] at [at] (snapped): the right half is a new chunk with the same fields. Null when not inside. */
    fun <T : TimeChunk<T>> split(chunk: T, at: Instant, zone: ZoneId): EditPlan<T>? {
        val cut = Snap.snap(at, zone)
        if (minutes(chunk.start, cut) < MIN_MINUTES || minutes(cut, chunk.end) < MIN_MINUTES) return null
        return EditPlan(inserts = listOf(chunk.withTimes(cut, chunk.end).withId(0)), updates = listOf(chunk to chunk.withTimes(chunk.start, cut)))
    }

    /**
     * Moves the boundary at the end of [left] and the start of [right] to [to] (snapped, inside [window]). With both,
     * it is their shared edge; with one, that chunk's edge moves into the gap beside it, up to its neighbour. Each side
     * keeps at least [MIN_SIDE_MINUTES]. Null when nothing can move.
     */
    fun <T : TimeChunk<T>> moveBoundary(existing: List<T>, left: T?, right: T?, to: Instant, window: Interval, zone: ZoneId): EditPlan<T>? {
        val snapped = Snap.snap(to, zone)
        val side = Duration.ofMinutes(MIN_SIDE_MINUTES)
        val (low, high) = when {
            left != null && right != null -> {
                if (left.end != right.start) return null
                left.start.plus(side) to right.end.minus(side)
            }
            left != null -> {
                val next = existing.filter { it.id != left.id && !it.start.isBefore(left.end) }.minOfOrNull { it.start } ?: window.end
                left.start.plus(side) to minOf(next, window.end)
            }
            right != null -> {
                val previous = existing.filter { it.id != right.id && !it.end.isAfter(right.start) }.maxOfOrNull { it.end } ?: window.start
                maxOf(previous, window.start) to right.end.minus(side)
            }
            else -> return null
        }
        val lowest = maxOf(low, window.start)
        val highest = minOf(high, window.end)
        if (highest.isBefore(lowest)) return null
        val edge = snapped.coerceIn(lowest, highest)
        val updates = ArrayList<Pair<T, T>>()
        if (left != null && left.end != edge) updates += left to left.withTimes(left.start, edge)
        if (right != null && right.start != edge) updates += right to right.withTimes(edge, right.end)
        return if (updates.isEmpty()) null else EditPlan(updates = updates)
    }

    /**
     * Joins [a] and the later [b], when they touch or only unrecorded time lies between them: [a] keeps its fields and
     * runs to [b]'s end. Null otherwise.
     */
    fun <T : TimeChunk<T>> merge(existing: List<T>, a: T, b: T): EditPlan<T>? {
        if (a.id == b.id || b.start.isBefore(a.end)) return null
        val between = Interval(a.end, b.start)
        if (!between.isEmpty && existing.any { it.id != a.id && it.id != b.id && span(it).overlaps(between) }) return null
        return EditPlan(updates = listOf(a to a.withTimes(a.start, b.end)), deletes = listOf(b))
    }

    fun <T : TimeChunk<T>> delete(chunk: T): EditPlan<T> = EditPlan(deletes = listOf(chunk))

    /**
     * *Happened as planned* for one waiting stretch: records for its unrecorded parts up to [now], copying the
     * source's category, module and task, and carrying its plan block id or key. Null when nothing is left to record.
     */
    fun confirm(records: List<RecordChunk>, waiting: DaySegment.Waiting, categories: List<TimeCategory>, now: Instant): EditPlan<RecordChunk>? {
        val upToNow = waiting.interval.intersect(Interval(waiting.interval.start, now)) ?: return null
        val parts = Intervals.subtract(upToNow, records.map { span(it) }).filter { it.minutes >= MIN_MINUTES }
        if (parts.isEmpty()) return null
        val template = recordFor(waiting.source, categories) ?: return null
        return EditPlan(inserts = parts.map { template.withTimes(it.start, it.end) })
    }

    /** *Confirm all as planned*: [confirm] for every waiting stretch of the day. */
    fun confirmAll(records: List<RecordChunk>, segments: List<DaySegment>, categories: List<TimeCategory>, now: Instant): EditPlan<RecordChunk>? {
        val inserts = ArrayList<RecordChunk>()
        for (waiting in segments.filterIsInstance<DaySegment.Waiting>()) {
            confirm(records + inserts, waiting, categories, now)?.let { inserts += it.inserts }
        }
        return if (inserts.isEmpty()) null else EditPlan(inserts = inserts)
    }

    /** A record of what [source] expected (its times to be set), or null when there is no category to give it. */
    fun recordFor(source: DaySource, categories: List<TimeCategory>): RecordChunk? {
        val interval = source.interval
        return when (source) {
            is DaySource.Plan -> RecordChunk(
                start = interval.start, end = interval.end, categoryId = source.block.categoryId, moduleId = source.block.moduleId,
                taskId = source.block.taskId, planBlockId = source.block.id, origin = RecordOrigin.CONFIRMED,
            )
            is DaySource.Class -> categoryId(categories, classCategory(source.occurrence.kind))?.let { id ->
                RecordChunk(
                    start = interval.start, end = interval.end, categoryId = id, moduleId = source.occurrence.moduleId,
                    sourceKey = source.key, origin = RecordOrigin.CONFIRMED,
                )
            }
            is DaySource.Event -> categoryId(categories, CategoryKey.OTHER)?.let { id ->
                RecordChunk(start = interval.start, end = interval.end, categoryId = id, sourceKey = source.key, origin = RecordOrigin.CONFIRMED)
            }
        }
    }

    /** What a class counts as when confirmed. */
    fun classCategory(kind: EntryKind): CategoryKey = when (kind) {
        EntryKind.LECTURE, EntryKind.SEMINAR, EntryKind.LAB, EntryKind.TUTORIAL -> CategoryKey.CLASS
        EntryKind.SPORT -> CategoryKey.EXERCISE
        EntryKind.SOCIETY -> CategoryKey.SOCIAL
        EntryKind.OTHER -> CategoryKey.OTHER
    }

    /** The category with [key] (a live one first), else the first live category. */
    fun categoryId(categories: List<TimeCategory>, key: CategoryKey): Long? =
        (categories.firstOrNull { it.systemKey == key && !it.archived } ?: categories.firstOrNull { it.systemKey == key }
            ?: categories.firstOrNull { !it.archived })?.id
}
