package io.github.matteopi.budgeting.core.planner.suggest

import io.github.matteopi.budgeting.core.planner.model.RevisionTopic
import java.time.LocalDate
import kotlin.math.roundToLong

/** When a revision topic comes back (docs/PLAN.md §13.4): at growing intervals after each review. */
object SpacedRepetition {

    /** Days after the 1st, 2nd, 3rd, 4th and 5th (or later) review. */
    val INTERVALS: List<Long> = listOf(1, 3, 7, 14, 30)

    /** Easy topics come back later, hard ones sooner. */
    fun factor(difficulty: Int): Double = when {
        difficulty <= 1 -> 1.3
        difficulty >= 3 -> 0.7
        else -> 1.0
    }

    /**
     * The date [topic] is next due: [start] (revision's first day) before any review, then the interval for the
     * number of [reviews] after the last one, scaled by difficulty and at least a day.
     */
    fun nextDue(topic: RevisionTopic, reviews: List<LocalDate>, start: LocalDate): LocalDate {
        val last = reviews.maxOrNull() ?: return start
        val interval = INTERVALS[minOf(reviews.size, INTERVALS.size) - 1]
        return last.plusDays((interval * factor(topic.difficulty)).roundToLong().coerceAtLeast(1))
    }
}
