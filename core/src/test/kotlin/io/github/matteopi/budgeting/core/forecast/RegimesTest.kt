package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegimesTest {
    private val monday = LocalDate.of(2024, 1, 1) // a known Monday

    @Test
    fun weekStartFindsTheMondayOnOrBefore() {
        assertEquals(monday, Regimes.weekStart(monday))
        assertEquals(monday, Regimes.weekStart(monday.plusDays(3))) // Thursday
        assertEquals(monday, Regimes.weekStart(monday.plusDays(6))) // Sunday
        assertEquals(monday.plusWeeks(1), Regimes.weekStart(monday.plusDays(7)))
    }

    @Test
    fun termContainingIsInclusiveOfBothEnds() {
        val term = TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2024, 9, 2), endDate = LocalDate.of(2024, 12, 13))
        assertEquals(term, Regimes.termContaining(term.startDate, listOf(term)))
        assertEquals(term, Regimes.termContaining(term.endDate, listOf(term)))
        assertEquals(term, Regimes.termContaining(LocalDate.of(2024, 10, 1), listOf(term)))
        assertNull(Regimes.termContaining(term.startDate.minusDays(1), listOf(term)))
        assertNull(Regimes.termContaining(term.endDate.plusDays(1), listOf(term)))
    }

    @Test
    fun regimeOfIsTermInsideATermElseHoliday() {
        val term = TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2024, 9, 2), endDate = LocalDate.of(2024, 12, 13))
        assertEquals(Regime.TERM, Regimes.regimeOf(LocalDate.of(2024, 10, 1), listOf(term)))
        assertEquals(Regime.HOLIDAY, Regimes.regimeOf(LocalDate.of(2024, 8, 1), listOf(term)))
        assertEquals(Regime.HOLIDAY, Regimes.regimeOf(LocalDate.of(2024, 1, 1), emptyList()))
    }

    @Test
    fun regimeOfWeekIsTheMajorityOfTheSevenDays() {
        // Term covers Thu..Sun of this week: 4 of 7 days -> TERM.
        val term = TermPeriod(id = 1, name = "T", startDate = monday.plusDays(3), endDate = monday.plusDays(6))
        assertEquals(Regime.TERM, Regimes.regimeOfWeek(monday, listOf(term)))

        // Term covers only Fri..Sun: 3 of 7 days -> HOLIDAY.
        val shortTerm = TermPeriod(id = 2, name = "T2", startDate = monday.plusDays(4), endDate = monday.plusDays(6))
        assertEquals(Regime.HOLIDAY, Regimes.regimeOfWeek(monday, listOf(shortTerm)))
    }
}
