package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.DayOfWeek
import java.time.LocalDate

/** Calendar helpers and TERM/HOLIDAY regime lookups. See `docs/PLAN.md` §5.2. */
object Regimes {
    /** The Monday on or before [date]. */
    fun weekStart(date: LocalDate): LocalDate {
        val offset = date.dayOfWeek.value - DayOfWeek.MONDAY.value
        return date.minusDays(offset.toLong())
    }

    /** The term (if any) whose inclusive range contains [date]. First match wins if terms overlap. */
    fun termContaining(date: LocalDate, terms: List<TermPeriod>): TermPeriod? =
        terms.firstOrNull { !date.isBefore(it.startDate) && !date.isAfter(it.endDate) }

    /** TERM if [date] falls inside any term (inclusive), else HOLIDAY. */
    fun regimeOf(date: LocalDate, terms: List<TermPeriod>): Regime =
        if (termContaining(date, terms) != null) Regime.TERM else Regime.HOLIDAY

    /**
     * The majority regime of the Mon–Sun week starting at [weekStart]: TERM if at least 4 of
     * the 7 days are inside a term, else HOLIDAY.
     */
    fun regimeOfWeek(weekStart: LocalDate, terms: List<TermPeriod>): Regime {
        var termDays = 0
        for (i in 0..6) {
            if (regimeOf(weekStart.plusDays(i.toLong()), terms) == Regime.TERM) termDays++
        }
        return if (termDays >= 4) Regime.TERM else Regime.HOLIDAY
    }
}
