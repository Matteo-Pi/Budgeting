package io.github.matteopi.budgeting.core.entry

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate

/** Ranks categories for the quick-add chips: most used recently first. */
object CategoryRanking {

    fun rank(
        categories: List<Category>,
        transactions: List<Transaction>,
        flow: Flow,
        today: LocalDate,
        windowDays: Int = 60,
    ): List<Category> {
        val windowStart = today.minusDays((windowDays - 1).toLong())
        val counts = transactions.asSequence()
            .filter {
                it.kind == TxKind.NORMAL && it.categoryId != null &&
                    !it.date.isBefore(windowStart) && !it.date.isAfter(today)
            }
            .groupingBy { it.categoryId!! }
            .eachCount()

        val eligible = categories.filter { !it.archived && !it.isSystem && it.flow == flow }
        return eligible.sortedWith(
            compareByDescending<Category> { counts[it.id] ?: 0 }.thenBy { it.sortOrder },
        )
    }
}
