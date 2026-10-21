package io.github.matteopi.budgeting.core.planner.suggest

import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.PlanOutcome
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the planner needs to know about the record and the plans (docs/PLAN.md §13.4), worked out from the rows so it
 * can't drift: time still planned (the part of each block after now), study recorded this week, the study already on
 * each day (which counts against the caps), and each revision topic's reviews.
 */
data class StudyTotals(
    val plannedByTask: Map<Long, Int>,
    val plannedByModule: Map<Long, Int>,
    val plannedByHabit: Map<Long, Int>,
    val studiedByModule: Map<Long, Int>,
    val usedByDay: Map<LocalDate, Int>,
    val topicReviews: Map<Long, List<LocalDate>>,
)

object StudyContext {

    /**
     * [plans] and [records] should cover this week and the revision period; [studyCategoryIds] are what counts as study
     * for the caps. Module and habit totals are this week's (Monday to Sunday of [today]).
     */
    fun totals(plans: List<PlanBlock>, records: List<RecordChunk>, studyCategoryIds: Set<Long>, now: Instant, today: LocalDate, zone: ZoneId): StudyTotals {
        val monday = HabitProgress.weekStart(today)
        val week = Interval(monday.atStartOfDay(zone).toInstant(), monday.plusDays(7).atStartOfDay(zone).toInstant())
        fun ahead(block: PlanBlock): Interval? = Interval(maxOf(block.start, now), block.end).takeIf { !it.isEmpty }
        val future = plans.mapNotNull { p -> ahead(p)?.let { p to it } }

        fun <K> sum(rows: List<Pair<K?, Long>>): Map<K, Int> =
            rows.filter { it.first != null }.groupBy({ it.first!! }, { it.second }).mapValues { (_, v) -> v.sum().toInt() }

        val used = HashMap<LocalDate, Int>()
        for ((p, part) in future) if (p.categoryId in studyCategoryIds) used.merge(part.start.atZone(zone).toLocalDate(), part.minutes.toInt(), Int::plus)
        for (r in records) {
            if (r.categoryId !in studyCategoryIds) continue
            val past = Interval(r.start, minOf(r.end, now)).takeIf { !it.isEmpty } ?: continue
            used.merge(past.start.atZone(zone).toLocalDate(), past.minutes.toInt(), Int::plus)
        }

        val reviews = plans.filter { it.topicId != null && !now.isBefore(it.end) }
            .filter { PlanOutcome.of(it, records, now).let { o -> o == Outcome.DONE || o == Outcome.PARTLY } }
            .groupBy({ it.topicId!! }, { it.start.atZone(zone).toLocalDate() })

        return StudyTotals(
            plannedByTask = sum(future.map { (p, part) -> p.taskId to part.minutes }),
            plannedByModule = sum(future.filter { (_, part) -> part.overlaps(week) }.map { (p, part) -> p.moduleId to (part.intersect(week)?.minutes ?: 0) }),
            plannedByHabit = sum(future.filter { (_, part) -> part.overlaps(week) }.map { (p, part) -> p.habitId to (part.intersect(week)?.minutes ?: 0) }),
            studiedByModule = sum(records.map { r -> r.moduleId to (Interval(r.start, r.end).intersect(week)?.minutes ?: 0) }),
            usedByDay = used,
            topicReviews = reviews,
        )
    }
}
