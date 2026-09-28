package io.github.matteopi.budgeting.ui.plan

import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate

/** Pure validation for the term dates editor (docs/PLAN.md §6.5): warn on overlaps and end-before-start. */
object TermValidation {
    fun endBeforeStart(start: LocalDate, end: LocalDate): Boolean = end.isBefore(start)

    /** True if the inclusive ranges `[a.startDate, a.endDate]` and `[b.startDate, b.endDate]` share a day. */
    fun overlaps(a: TermPeriod, b: TermPeriod): Boolean =
        !a.startDate.isAfter(b.endDate) && !b.startDate.isAfter(a.endDate)

    /** Existing terms (other than [excludingId], the term being edited) that overlap `[start, end]`. */
    fun findOverlaps(
        start: LocalDate,
        end: LocalDate,
        excludingId: Long?,
        existing: List<TermPeriod>,
    ): List<TermPeriod> {
        val candidate = TermPeriod(id = excludingId ?: -1, name = "", startDate = start, endDate = end)
        return existing.filter { it.id != excludingId && overlaps(candidate, it) }
    }
}
