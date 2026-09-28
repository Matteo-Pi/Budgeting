package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceTest {
    @Test
    fun monthlyOnThe31stLandsOnTheLastDayOfShortMonths() {
        val dates = Recurrence.occurrences(
            Frequency.MONTHLY, 1, LocalDate.of(2024, 1, 31), null,
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 5, 1),
        )
        assertEquals(
            listOf(
                LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 2, 29), // 2024 is a leap year
                LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 30), // computed fresh from Jan 31 each time -- never drifts to May
            ),
            dates,
        )
    }

    @Test
    fun fortnightlyStepsByTwoWeeksFromTheStart() {
        val dates = Recurrence.occurrences(
            Frequency.WEEKLY, 2, LocalDate.of(2024, 1, 1), null,
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1),
        )
        assertEquals(
            listOf(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 15), LocalDate.of(2024, 1, 29)),
            dates,
        )
    }

    @Test
    fun intervalBelowOneIsTreatedAsOne() {
        val dates = Recurrence.occurrences(
            Frequency.DAILY, 0, LocalDate.of(2024, 1, 1), null,
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3),
        )
        assertEquals(listOf(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3)), dates)
    }

    @Test
    fun stopsAtEndDateEvenWhenToIsLater() {
        val dates = Recurrence.occurrences(
            Frequency.WEEKLY, 1, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 15),
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 1),
        )
        assertEquals(
            listOf(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 8), LocalDate.of(2024, 1, 15)),
            dates,
        )
    }

    @Test
    fun onlyReturnsOccurrencesFromFromOnwards() {
        val dates = Recurrence.occurrences(
            Frequency.WEEKLY, 1, LocalDate.of(2024, 1, 1), null,
            LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 31),
        )
        assertEquals(listOf(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 1, 22), LocalDate.of(2024, 1, 29)), dates)
    }

    @Test
    fun ruleOverloadDelegatesToTheFieldOverload() {
        val rule = RecurringRule(
            id = 1, label = "Rent", accountId = 1, categoryId = null, amount = Money.pounds(-500),
            frequency = Frequency.MONTHLY, startDate = LocalDate.of(2024, 1, 31), postingMode = PostingMode.AUTO,
        )
        val dates = Recurrence.occurrences(rule, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 1))
        assertEquals(listOf(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29)), dates)
    }

    @Test
    fun yearlyOnALeapDayClampsInNonLeapYearsWithoutDrifting() {
        val dates = Recurrence.occurrences(
            Frequency.YEARLY, 1, LocalDate.of(2020, 2, 29), null,
            LocalDate.of(2020, 1, 1), LocalDate.of(2023, 1, 1),
        )
        // plusYears from Feb 29 clamps to Feb 28 in non-leap years, computed fresh each time, so
        // 2023's occurrence (Feb 28) is after `to` (Jan 1) and correctly excluded.
        assertEquals(
            listOf(LocalDate.of(2020, 2, 29), LocalDate.of(2021, 2, 28), LocalDate.of(2022, 2, 28)),
            dates,
        )
    }

    @Test
    fun emptyWhenToIsBeforeStart() {
        val dates = Recurrence.occurrences(
            Frequency.WEEKLY, 1, LocalDate.of(2024, 6, 1), null,
            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10),
        )
        assertEquals(emptyList(), dates)
    }
}
