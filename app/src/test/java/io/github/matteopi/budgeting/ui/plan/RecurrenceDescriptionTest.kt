package io.github.matteopi.budgeting.ui.plan

import io.github.matteopi.budgeting.core.model.Frequency
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceDescriptionTest {
    @Test
    fun dailyEveryOne() {
        assertEquals(
            "every day",
            RecurrenceDescription.describe(Frequency.DAILY, 1, LocalDate.of(2026, 9, 28)),
        )
    }

    @Test
    fun dailyEveryN() {
        assertEquals(
            "every 3 days",
            RecurrenceDescription.describe(Frequency.DAILY, 3, LocalDate.of(2026, 9, 28)),
        )
    }

    @Test
    fun weeklyNamesTheDay() {
        // 2026-09-28 is a Monday.
        assertEquals(
            "every week on Monday",
            RecurrenceDescription.describe(Frequency.WEEKLY, 1, LocalDate.of(2026, 9, 28)),
        )
        assertEquals(
            "every 2 weeks on Monday",
            RecurrenceDescription.describe(Frequency.WEEKLY, 2, LocalDate.of(2026, 9, 28)),
        )
    }

    @Test
    fun monthlyUsesOrdinalDay() {
        assertEquals(
            "every month on the 1st",
            RecurrenceDescription.describe(Frequency.MONTHLY, 1, LocalDate.of(2026, 9, 1)),
        )
        assertEquals(
            "every month on the 2nd",
            RecurrenceDescription.describe(Frequency.MONTHLY, 1, LocalDate.of(2026, 9, 2)),
        )
        assertEquals(
            "every month on the 3rd",
            RecurrenceDescription.describe(Frequency.MONTHLY, 1, LocalDate.of(2026, 9, 3)),
        )
        assertEquals(
            "every month on the 4th",
            RecurrenceDescription.describe(Frequency.MONTHLY, 1, LocalDate.of(2026, 9, 4)),
        )
        assertEquals(
            "every month on the 11th",
            RecurrenceDescription.describe(Frequency.MONTHLY, 1, LocalDate.of(2026, 9, 11)),
        )
        assertEquals(
            "every month on the 21st",
            RecurrenceDescription.describe(Frequency.MONTHLY, 1, LocalDate.of(2026, 9, 21)),
        )
        assertEquals(
            "every 3 months on the 31st",
            RecurrenceDescription.describe(Frequency.MONTHLY, 3, LocalDate.of(2026, 1, 31)),
        )
    }

    @Test
    fun yearlyNamesMonthAndDay() {
        assertEquals(
            "every year on 5th January",
            RecurrenceDescription.describe(Frequency.YEARLY, 1, LocalDate.of(2026, 1, 5)),
        )
    }
}
