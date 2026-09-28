package io.github.matteopi.budgeting.ui.plan

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money

/** Pure helpers for the student loan helper (docs/PLAN.md §6.5). */
object LoanSplit {
    /**
     * Splits a year's total into three termly instalments: the first two get the whole-pence
     * floor of a third, the last gets whatever remains, so the three always sum back to [total]
     * ("split 33/33/34 in pence, remainder on the last").
     */
    fun splitThree(total: Money): List<Money> {
        val third = total.pence / 3
        val last = total.pence - 2 * third
        return listOf(Money(third), Money(third), Money(last))
    }

    /**
     * The category new student-loan instalments should use: the IN, major-income-by-default
     * category named "Student loan", or else the first IN category flagged major-income-by-default.
     */
    fun studentLoanCategory(categories: List<Category>): Category? =
        categories.firstOrNull { it.flow == CategoryFlow.IN && it.isMajorIncomeDefault && it.name == "Student loan" }
            ?: categories.firstOrNull { it.flow == CategoryFlow.IN && it.isMajorIncomeDefault }
}
