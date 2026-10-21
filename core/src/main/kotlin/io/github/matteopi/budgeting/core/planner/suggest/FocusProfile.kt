package io.github.matteopi.budgeting.core.planner.suggest

import io.github.matteopi.budgeting.core.planner.day.PlanOutcome
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** One 2-hour time of day ([hour] is its first hour, 0, 2 … 22), on weekdays or at the weekend. */
data class FocusBucket(val weekend: Boolean, val hour: Int)

/** How study goes in a bucket: the mean focus of [focusSamples] rated chunks, and the share of [planned] study skipped. */
data class FocusStats(val meanFocus: Double?, val focusSamples: Int, val skipRate: Double?, val planned: Int)

/**
 * When the user studies and focuses best (docs/PLAN.md §13.4), from the record: the mean focus and skip rate of study
 * per 2-hour time-of-day bucket, for weekdays and weekends apart, with the sample counts.
 */
data class FocusProfile(val buckets: Map<FocusBucket, FocusStats>) {

    /** The scoring bonus at [start]: (mean focus − 3) once a bucket has [MIN_SAMPLES] rated chunks, else 0. */
    fun bonus(start: Instant, zone: ZoneId): Double {
        val stats = buckets[bucketOf(start, zone)] ?: return 0.0
        val mean = stats.meanFocus ?: return 0.0
        return if (stats.focusSamples >= MIN_SAMPLES) mean - 3.0 else 0.0
    }

    companion object {
        const val MIN_SAMPLES = 5

        fun bucketOf(at: Instant, zone: ZoneId): FocusBucket {
            val local = at.atZone(zone)
            return FocusBucket(local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY, local.hour / 2 * 2)
        }

        /**
         * [records] of the [studyCategoryIds] with a focus rating give the means (by their start); [plans] of those
         * categories that have ended give the skip rates.
         */
        fun from(records: List<RecordChunk>, plans: List<PlanBlock>, studyCategoryIds: Set<Long>, now: Instant, zone: ZoneId): FocusProfile {
            val rated = records.filter { it.categoryId in studyCategoryIds && it.focus != null }.groupBy { bucketOf(it.start, zone) }
            val ended = plans.filter { it.categoryId in studyCategoryIds && !now.isBefore(it.end) }.groupBy { bucketOf(it.start, zone) }
            val buckets = (rated.keys + ended.keys).associateWith { bucket ->
                val focus = rated[bucket].orEmpty()
                val planned = ended[bucket].orEmpty()
                val skipped = planned.count { PlanOutcome.of(it, records, now) == Outcome.SKIPPED }
                FocusStats(
                    meanFocus = if (focus.isEmpty()) null else focus.mapNotNull { it.focus }.average(),
                    focusSamples = focus.size,
                    skipRate = if (planned.isEmpty()) null else skipped.toDouble() / planned.size,
                    planned = planned.size,
                )
            }
            return FocusProfile(buckets)
        }
    }
}
