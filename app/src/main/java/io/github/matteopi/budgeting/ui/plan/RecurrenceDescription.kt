package io.github.matteopi.budgeting.ui.plan

import io.github.matteopi.budgeting.core.model.Frequency
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Plain-English description of a recurring rule's schedule, e.g. "every month on the 1st". */
object RecurrenceDescription {
    fun describe(frequency: Frequency, interval: Int, startDate: LocalDate): String {
        val n = if (interval < 1) 1 else interval
        return when (frequency) {
            Frequency.DAILY -> if (n == 1) "every day" else "every $n days"
            Frequency.WEEKLY -> {
                val day = startDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
                if (n == 1) "every week on $day" else "every $n weeks on $day"
            }
            Frequency.MONTHLY -> {
                val day = ordinal(startDate.dayOfMonth)
                if (n == 1) "every month on the $day" else "every $n months on the $day"
            }
            Frequency.YEARLY -> {
                val month = startDate.month.getDisplayName(TextStyle.FULL, Locale.UK)
                val day = ordinal(startDate.dayOfMonth)
                if (n == 1) "every year on $day $month" else "every $n years on $day $month"
            }
        }
    }

    private fun ordinal(day: Int): String {
        val suffix = if (day in 11..13) {
            "th"
        } else {
            when (day % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$day$suffix"
    }
}
