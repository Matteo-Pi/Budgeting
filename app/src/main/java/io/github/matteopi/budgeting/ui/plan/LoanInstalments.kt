package io.github.matteopi.budgeting.ui.plan

import java.time.LocalDate

/**
 * One of the three termly student loan instalments the user can fine-tune. [date] is null until
 * it is known: an instalment with no upcoming term to anchor to starts blank rather than dated
 * today (a made-up date today would create a "did your loan arrive?" prompt straight away).
 */
data class LoanInstalment(val date: LocalDate?, val amountText: String = "")

/** Pure checks shared by the student loan helper and the onboarding "Money in" step. */
object LoanInstalments {
    const val MISSING_DATE_MESSAGE = "Pick a date for every instalment."

    /** Every instalment's date, or null if any of them hasn't been picked yet (nothing may be saved then). */
    fun datesOrNull(instalments: List<LoanInstalment>): List<LocalDate>? {
        val dates = instalments.map { it.date }
        return if (dates.any { it == null }) null else dates.filterNotNull()
    }
}
