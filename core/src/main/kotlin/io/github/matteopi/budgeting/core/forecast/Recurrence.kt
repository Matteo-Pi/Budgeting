package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.RecurringRule
import java.time.LocalDate

/** Expands recurring rules into occurrence dates. See `docs/PLAN.md` §5.3. */
object Recurrence {
    /** Occurrence dates of [rule] within `[from, to]` inclusive, ascending. */
    fun occurrences(rule: RecurringRule, from: LocalDate, to: LocalDate): List<LocalDate> =
        occurrences(rule.frequency, rule.interval, rule.startDate, rule.endDate, from, to)

    /**
     * Occurrence `k` is `start.plusDays/plusWeeks/plusMonths/plusYears(k * interval)`, computed
     * fresh from [start] every time (never chained from the previous occurrence). That keeps a
     * rule anchored on the 31st landing on the 31st in long months and on the last day of short
     * ones, rather than drifting. Stops once an occurrence is after [end] (if given) or after
     * [to]; only occurrences on or after [from] are returned. `interval < 1` is treated as 1.
     */
    fun occurrences(
        frequency: Frequency,
        interval: Int,
        start: LocalDate,
        end: LocalDate?,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        val step = (if (interval < 1) 1 else interval).toLong()
        val lastAllowed = if (end != null && end.isBefore(to)) end else to
        val result = mutableListOf<LocalDate>()
        var k = 0L
        while (true) {
            val date = when (frequency) {
                Frequency.DAILY -> start.plusDays(k * step)
                Frequency.WEEKLY -> start.plusWeeks(k * step)
                Frequency.MONTHLY -> start.plusMonths(k * step)
                Frequency.YEARLY -> start.plusYears(k * step)
            }
            if (date.isAfter(lastAllowed)) break
            if (!date.isBefore(from)) result.add(date)
            k++
        }
        return result
    }
}
