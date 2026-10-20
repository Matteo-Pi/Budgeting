package io.github.matteopi.budgeting.ui.planner.day

import io.github.matteopi.budgeting.core.planner.day.ChunkField
import io.github.matteopi.budgeting.core.planner.day.ChunkRules
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The chunk sheet's fields (docs/PLAN.md §13.9). Times are typed on the day's clock: a time before the window's start
 * is on the next date (a fixed day running past midnight), and an end at or before the start is the next day too.
 * [record] or [plan] is the chunk being edited; neither for a new one.
 */
data class ChunkForm(
    val record: RecordChunk? = null,
    val plan: PlanBlock? = null,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val categoryId: Long? = null,
    val moduleId: Long? = null,
    val taskId: Long? = null,
    val focus: Int? = null,
    val note: String = "",
    /** What a *Change* of a waiting chunk confirms. */
    val planBlockId: Long? = null,
    val sourceKey: String? = null,
) {
    val isNew: Boolean get() = record == null && plan == null

    fun startInstant(window: Interval, zone: ZoneId): Instant? = start?.let { instantOf(it, window, zone) }

    fun endInstant(window: Interval, zone: ZoneId): Instant? {
        val s = startInstant(window, zone) ?: return null
        val e = end?.let { instantOf(it, window, zone) } ?: return null
        return if (e.isAfter(s)) e else ZonedDateTime.of(e.atZone(zone).toLocalDate().plusDays(1), end, zone).toInstant()
    }

    fun errors(window: Interval, zone: ZoneId, categories: List<TimeCategory>): Map<ChunkField, String> =
        ChunkRules.errors(startInstant(window, zone), endInstant(window, zone), categories.firstOrNull { it.id == categoryId }, focus)

    /** The record this form saves (the edited one with its id, or a new one). */
    fun toRecord(window: Interval, zone: ZoneId): RecordChunk? {
        val s = startInstant(window, zone) ?: return null
        val e = endInstant(window, zone) ?: return null
        val category = categoryId ?: return null
        val base = record ?: RecordChunk(start = s, end = e, categoryId = category, planBlockId = planBlockId, sourceKey = sourceKey)
        return base.copy(start = s, end = e, categoryId = category, moduleId = moduleId, taskId = taskId, focus = focus, note = note.trim())
    }

    fun toPlan(window: Interval, zone: ZoneId): PlanBlock? {
        val s = startInstant(window, zone) ?: return null
        val e = endInstant(window, zone) ?: return null
        val category = categoryId ?: return null
        val base = plan ?: PlanBlock(start = s, end = e, categoryId = category)
        return base.copy(start = s, end = e, categoryId = category, moduleId = moduleId, taskId = taskId, note = note.trim())
    }

    companion object {
        /** [time] on the window's clock: on its first date, or the next when that is before the window starts. */
        fun instantOf(time: LocalTime, window: Interval, zone: ZoneId): Instant {
            val date = window.start.atZone(zone).toLocalDate()
            val first = ZonedDateTime.of(date, time, zone).toInstant()
            return if (first.isBefore(window.start)) ZonedDateTime.of(date.plusDays(1), time, zone).toInstant() else first
        }

        private fun local(at: Instant, zone: ZoneId): LocalTime = at.atZone(zone).toLocalTime().withSecond(0).withNano(0)

        fun of(record: RecordChunk, zone: ZoneId) = ChunkForm(
            record = record, start = local(record.start, zone), end = local(record.end, zone), categoryId = record.categoryId,
            moduleId = record.moduleId, taskId = record.taskId, focus = record.focus, note = record.note,
            planBlockId = record.planBlockId, sourceKey = record.sourceKey,
        )

        fun of(plan: PlanBlock, zone: ZoneId) = ChunkForm(
            plan = plan, start = local(plan.start, zone), end = local(plan.end, zone), categoryId = plan.categoryId,
            moduleId = plan.moduleId, taskId = plan.taskId, note = plan.note,
        )

        /** A new chunk from [start] to [end] (a gap, or what a waiting chunk expected). */
        fun new(start: Instant, end: Instant, zone: ZoneId, categoryId: Long? = null, moduleId: Long? = null, taskId: Long? = null) =
            ChunkForm(start = local(start, zone), end = local(end, zone), categoryId = categoryId, moduleId = moduleId, taskId = taskId)
    }
}
