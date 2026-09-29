package io.github.matteopi.budgeting.ui.onboarding

import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class InstalmentDefaultsTest {
    private val today = LocalDate.of(2026, 9, 28)

    @Test
    fun usesTheStartOfTheNextThreeTerms() {
        val terms = listOf(
            TermPeriod(id = 1, name = "Autumn", startDate = today, endDate = LocalDate.of(2026, 12, 12)),
            TermPeriod(id = 2, name = "Spring", startDate = LocalDate.of(2027, 1, 11), endDate = LocalDate.of(2027, 3, 26)),
            TermPeriod(id = 3, name = "Summer", startDate = LocalDate.of(2027, 4, 26), endDate = LocalDate.of(2027, 6, 18)),
        )
        // Autumn starts today, not after today, so it doesn't count as "upcoming"; the third
        // instalment has no term to anchor to, so it stays blank instead of defaulting to today.
        assertEquals(
            listOf(LocalDate.of(2027, 1, 11), LocalDate.of(2027, 4, 26), null),
            InstalmentDefaults.defaultDates(terms, today),
        )
    }

    @Test
    fun ignoresTermsThatHaveAlreadyStarted() {
        val terms = listOf(TermPeriod(id = 1, name = "Autumn", startDate = today.minusDays(1), endDate = today.plusDays(60)))
        assertEquals(listOf(null, null, null), InstalmentDefaults.defaultDates(terms, today))
    }

    @Test
    fun sortsOutOfOrderTerms() {
        val terms = listOf(
            TermPeriod(id = 2, name = "Spring", startDate = LocalDate.of(2027, 1, 11), endDate = LocalDate.of(2027, 3, 26)),
            TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2026, 10, 5), endDate = LocalDate.of(2026, 12, 12)),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2027, 1, 11), null),
            InstalmentDefaults.defaultDates(terms, today),
        )
    }

    @Test
    fun noUpcomingTermsLeavesEveryDateBlank() {
        assertEquals(listOf(null, null, null), InstalmentDefaults.defaultDates(emptyList(), today))
    }

    @Test
    fun neverDefaultsToToday() {
        val dates = InstalmentDefaults.defaultDates(emptyList(), today)
        assertEquals(false, dates.contains(today))
    }

    @Test
    fun onlyTakesTheFirstThreeUpcomingTerms() {
        val terms = (1..5).map { n ->
            TermPeriod(id = n.toLong(), name = "Term $n", startDate = today.plusMonths(n.toLong()), endDate = today.plusMonths(n.toLong()).plusDays(60))
        }
        assertEquals(
            listOf(today.plusMonths(1), today.plusMonths(2), today.plusMonths(3)),
            InstalmentDefaults.defaultDates(terms, today),
        )
    }
}
