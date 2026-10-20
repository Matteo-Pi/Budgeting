package io.github.matteopi.budgeting.core.planner.timetable

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import java.time.Duration
import java.util.Locale

/**
 * How long it takes to get between places (docs/PLAN.md §12.6): [fromHome] by place, and [defaultMinutes] between
 * two different places or from home to a place without its own time. The same place (ignoring case) takes no time,
 * and a blank place is treated as "nowhere in particular".
 */
data class TravelTimes(
    val defaultMinutes: Int = 15,
    val fromHome: Map<String, Int> = emptyMap(),
) {
    private fun key(place: String) = place.trim().lowercase(Locale.ROOT)

    fun between(a: String, b: String): Int = when {
        a.isBlank() || b.isBlank() -> 0
        key(a) == key(b) -> 0
        else -> defaultMinutes
    }

    fun fromHome(place: String): Int =
        if (place.isBlank()) 0 else fromHome.entries.firstOrNull { key(it.key) == key(place) }?.value ?: defaultMinutes
}

/** A problem between two classes on the same day. */
data class Clash(val first: Occurrence, val second: Occurrence, val kind: ClashKind, val gapMinutes: Long)

enum class ClashKind {
    /** They overlap. */
    OVERLAP,

    /** The gap between them is shorter than the time to get from one place to the other. */
    TIGHT,
}

/** Clashes between classes (docs/PLAN.md §13.4). */
object Clashes {

    fun find(occurrences: List<Occurrence>, travel: TravelTimes = TravelTimes()): List<Clash> {
        val clashes = mutableListOf<Clash>()
        for ((_, day) in occurrences.groupBy { it.date }) {
            val sorted = day.sortedWith(compareBy<Occurrence> { it.start }.thenBy { it.end })
            for (i in sorted.indices) {
                for (j in i + 1 until sorted.size) {
                    val a = sorted[i]
                    val b = sorted[j]
                    if (b.start.isBefore(a.end)) {
                        clashes += Clash(a, b, ClashKind.OVERLAP, 0)
                    } else if (j == i + 1) {
                        val gap = Duration.between(a.end, b.start).toMinutes()
                        if (gap < travel.between(a.location, b.location)) clashes += Clash(a, b, ClashKind.TIGHT, gap)
                    }
                }
            }
        }
        return clashes
    }
}

/** An item placed in a lane: [lane] of [lanes] side by side. */
data class Placed<T>(val item: T, val lane: Int, val lanes: Int)

/**
 * Side-by-side lanes for overlapping items (docs/PLAN.md §13.4): items that overlap share their cluster's width, each
 * in the first free lane. The week grid and the day canvas draw them as they are.
 */
object Lanes {
    fun <T> assign(items: List<T>, start: (T) -> Long, end: (T) -> Long): List<Placed<T>> {
        val sorted = items.sortedWith(compareBy<T>({ start(it) }, { end(it) }))
        val result = ArrayList<Placed<T>>(sorted.size)
        var cluster = mutableListOf<Pair<T, Int>>()
        val laneEnds = mutableListOf<Long>()
        var clusterEnd = Long.MIN_VALUE

        fun flush() {
            val lanes = (cluster.maxOfOrNull { it.second } ?: -1) + 1
            cluster.forEach { (item, lane) -> result += Placed(item, lane, lanes) }
            cluster = mutableListOf()
            laneEnds.clear()
        }

        for (item in sorted) {
            val s = start(item)
            val e = end(item)
            if (cluster.isNotEmpty() && s >= clusterEnd) flush()
            var lane = laneEnds.indexOfFirst { it <= s }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds += e
            } else {
                laneEnds[lane] = e
            }
            cluster += item to lane
            clusterEnd = if (cluster.size == 1) e else maxOf(clusterEnd, e)
        }
        if (cluster.isNotEmpty()) flush()
        return result
    }
}

/** The fields of the timetable entry editor that can carry an error. */
enum class EntryField { TITLE, TIME, WEEKS, DATE }

/** What a timetable entry needs before it can be saved (docs/PLAN.md §13.4). */
object EntryRules {
    fun errors(entry: TimetableEntry): Map<EntryField, String> {
        val errors = LinkedHashMap<EntryField, String>()
        if (entry.title.isBlank()) errors[EntryField.TITLE] = "Enter a title."
        if (entry.start == entry.end) errors[EntryField.TIME] = "The end must be after the start."
        when (val p = entry.pattern) {
            is WeekPattern.TeachingWeeks -> if (p.weeks.isEmpty()) errors[EntryField.WEEKS] = "Choose at least one week."
            else -> Unit
        }
        return errors
    }

    /** A note for the editor when the pattern can't be placed yet: chosen, odd and even weeks need term dates. */
    fun needsTermDates(entry: TimetableEntry, terms: List<TermPeriod>): Boolean =
        terms.isEmpty() && entry.pattern != WeekPattern.EveryWeek && entry.pattern !is WeekPattern.Once
}

/** Copying a term's own entries into another term (docs/PLAN.md §12.4): new entries for [to], same times and weeks. */
object TermCopy {
    fun rows(entries: List<TimetableEntry>, from: TermPeriod, to: TermPeriod): List<TimetableEntry> =
        entries.filter { it.termId == from.id && !it.archived && it.pattern !is WeekPattern.Once }
            .map { it.copy(id = 0, termId = to.id) }
}
