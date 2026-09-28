package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction

/**
 * Imported rows dated before the account's opening-balance date would double count (the opening
 * balance already includes them). Adjusts the opening transaction so it doesn't.
 */
object OpeningBalanceAdjuster {

    fun adjust(opening: Transaction, imported: List<ParsedRow>): Transaction? {
        val earlier = imported.filter { it.date.isBefore(opening.date) }
        if (earlier.isEmpty()) return null

        val total = Money(earlier.sumOf { it.amount.pence })
        val earliestDate = earlier.minOf { it.date }
        return opening.copy(amount = opening.amount - total, date = earliestDate.minusDays(1))
    }
}
