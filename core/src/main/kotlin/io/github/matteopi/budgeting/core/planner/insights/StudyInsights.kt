package io.github.matteopi.budgeting.core.planner.insights

import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.PlanOutcome
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.todo.DeadlineStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** When a block starts: before 12:00, from 12:00 to 17:00, or from 17:00. */
enum class DayPart { MORNING, AFTERNOON, EVENING;

    companion object {
        fun of(at: Instant, zone: ZoneId): DayPart = at.atZone(zone).hour.let { h ->
            when {
                h < 12 -> MORNING
                h < 17 -> AFTERNOON
                else -> EVENING
            }
        }
    }
}

/** A block's length: 45 minutes or less, 46 to 75, or more than 75. */
enum class BlockLength { SHORT, MEDIUM, LONG;

    companion object {
        fun of(minutes: Long): BlockLength = when {
            minutes <= 45 -> SHORT
            minutes <= 75 -> MEDIUM
            else -> LONG
        }
    }
}

/** How planned blocks went. */
data class OutcomeCounts(val done: Int = 0, val partly: Int = 0, val skipped: Int = 0) {
    val total: Int get() = done + partly + skipped

    /** The share done, or null with none. */
    val doneRate: Double? get() = if (total == 0) null else done.toDouble() / total

    val skipRate: Double? get() = if (total == 0) null else skipped.toDouble() / total

    operator fun plus(outcome: Outcome): OutcomeCounts = when (outcome) {
        Outcome.DONE -> copy(done = done + 1)
        Outcome.PARTLY -> copy(partly = partly + 1)
        Outcome.SKIPPED -> copy(skipped = skipped + 1)
    }
}

/** Planned study against what happened: in all, and by when blocks start, their weekday and their length. */
data class PlanStats(
    val all: OutcomeCounts,
    val byPart: Map<DayPart, OutcomeCounts>,
    val byWeekday: Map<DayOfWeek, OutcomeCounts>,
    val byLength: Map<BlockLength, OutcomeCounts>,
    val plannedMinutes: Long,
)

/** A module's study in a period against its weekly target scaled to the period's length (null: no target). */
data class ModuleStudy(val module: Module, val minutes: Long, val targetMinutes: Long?)

/**
 * A deadline's work: [doneMinutes] recorded, [leftMinutes] (null without an estimate), the days until it is due, the
 * minutes a day recorded on it over the last [StudyInsights.PACE_DAYS] days, and [atRisk] when that pace would not
 * finish the work left in time.
 */
data class DeadlineInsight(
    val task: Task,
    val doneMinutes: Int,
    val leftMinutes: Int?,
    val daysLeft: Long,
    val pacePerDay: Double,
    val atRisk: Boolean,
)

/** Study (docs/PLAN.md §12.10, §13.4): modules against their targets, planned against done, and deadlines. */
object StudyInsights {

    /** The days over which a deadline's pace is measured. */
    const val PACE_DAYS = 7L

    /** Each module that has a target or was studied, with its minutes of [studyCategoryIds] in [period]. */
    fun modules(records: List<RecordChunk>, modules: List<Module>, studyCategoryIds: Set<Long>, period: Period, zone: ZoneId): List<ModuleStudy> {
        val window = period.interval(zone)
        val studied = HashMap<Long, Long>()
        for (r in records) {
            val module = r.moduleId ?: continue
            if (r.categoryId !in studyCategoryIds) continue
            val part = Interval(r.start, r.end).intersect(window) ?: continue
            studied[module] = (studied[module] ?: 0) + part.minutes
        }
        return modules.filter { !it.archived || (studied[it.id] ?: 0) > 0 }.mapNotNull { module ->
            val target = module.weeklyTargetMinutes?.takeIf { it > 0 }?.let { (it * period.days / 7.0).roundToLong() }
            val minutes = studied[module.id] ?: 0
            if (target == null && minutes == 0L) null else ModuleStudy(module, minutes, target)
        }.sortedWith(compareByDescending<ModuleStudy> { it.minutes }.thenBy { it.module.code })
    }

    /** The study blocks that started in [period] and have ended by [now], with how each went. */
    fun plans(plans: List<PlanBlock>, records: List<RecordChunk>, studyCategoryIds: Set<Long>, period: Period, now: Instant, zone: ZoneId): PlanStats {
        val window = period.interval(zone)
        var all = OutcomeCounts()
        val byPart = HashMap<DayPart, OutcomeCounts>()
        val byWeekday = HashMap<DayOfWeek, OutcomeCounts>()
        val byLength = HashMap<BlockLength, OutcomeCounts>()
        var planned = 0L
        for (block in plans) {
            if (block.categoryId !in studyCategoryIds || block.start !in window) continue
            val outcome = PlanOutcome.of(block, records, now) ?: continue
            val minutes = Interval(block.start, block.end).minutes
            planned += minutes
            all += outcome
            DayPart.of(block.start, zone).let { byPart[it] = (byPart[it] ?: OutcomeCounts()) + outcome }
            block.start.atZone(zone).dayOfWeek.let { byWeekday[it] = (byWeekday[it] ?: OutcomeCounts()) + outcome }
            BlockLength.of(minutes).let { byLength[it] = (byLength[it] ?: OutcomeCounts()) + outcome }
        }
        return PlanStats(all, byPart, byWeekday, byLength, planned)
    }

    /**
     * The open deadlines with a due date of [tasks] (steps count towards their deadline), soonest first. [studiedByTask]
     * is all the time recorded per task; [records] give the recent pace.
     */
    fun deadlines(tasks: List<Task>, studiedByTask: Map<Long, Int>, records: List<RecordChunk>, today: LocalDate, zone: ZoneId): List<DeadlineInsight> {
        val steps = tasks.filter { it.parentId != null }.groupBy { it.parentId }
        val recent = Interval(today.minusDays(PACE_DAYS).atStartOfDay(zone).toInstant(), today.atStartOfDay(zone).toInstant())
        return tasks.filter { it.isDeadline && it.isOpen && it.parentId == null && it.dueDate != null && !it.dueDate.isBefore(today) }
            .map { deadline ->
                val own = steps[deadline.id].orEmpty()
                val ids = (own.map { it.id } + deadline.id).toSet()
                val status = DeadlineStatus.of(deadline, own, studiedByTask, today)
                val recentMinutes = records.filter { it.taskId in ids }.sumOf { r -> Interval(r.start, r.end).intersect(recent)?.minutes ?: 0L }
                val pace = recentMinutes.toDouble() / PACE_DAYS
                val left = status.workLeftMinutes
                // Due today still leaves today; the pace counts the days from today to the due date.
                val days = ChronoUnit.DAYS.between(today, deadline.dueDate!!) + 1
                DeadlineInsight(
                    task = deadline,
                    doneMinutes = status.studiedMinutes,
                    leftMinutes = left,
                    daysLeft = status.daysLeft,
                    pacePerDay = pace,
                    atRisk = left != null && left > 0 && pace * days < left,
                )
            }
            .sortedWith(compareBy<DeadlineInsight> { it.task.dueDate }.thenBy { it.task.id })
    }
}

/** The mean focus of [samples] rated chunks. */
data class FocusMean(val mean: Double, val samples: Int)

/** Focus (docs/PLAN.md §12.10): the mean rating of study by 2-hour time of day (its first hour) and by chunk length. */
data class FocusSummary(val overall: FocusMean?, val byHour: Map<Int, FocusMean>, val byLength: Map<BlockLength, FocusMean>)

object FocusInsights {

    /** The rated chunks of [studyCategoryIds] that start in [period]. */
    fun summary(records: List<RecordChunk>, studyCategoryIds: Set<Long>, period: Period, zone: ZoneId): FocusSummary {
        val window = period.interval(zone)
        val rated = records.filter { it.categoryId in studyCategoryIds && it.focus != null && it.start in window }
        fun mean(list: List<RecordChunk>) = FocusMean(list.mapNotNull { it.focus }.average(), list.size)
        return FocusSummary(
            overall = if (rated.isEmpty()) null else mean(rated),
            byHour = rated.groupBy { it.start.atZone(zone).hour / 2 * 2 }.mapValues { mean(it.value) },
            byLength = rated.groupBy { BlockLength.of(Interval(it.start, it.end).minutes) }.mapValues { mean(it.value) },
        )
    }
}
