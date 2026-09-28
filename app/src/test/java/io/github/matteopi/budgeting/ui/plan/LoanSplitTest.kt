package io.github.matteopi.budgeting.ui.plan

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.sum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoanSplitTest {
    @Test
    fun splitsEvenAmountsEqually() {
        val parts = LoanSplit.splitThree(Money.pounds(3000))
        assertEquals(listOf(Money.pounds(1000), Money.pounds(1000), Money.pounds(1000)), parts)
        assertEquals(Money.pounds(3000), parts.sum())
    }

    @Test
    fun putsTheRemainderOnTheLastInstalment() {
        val total = Money(310031) // £3,100.31
        val parts = LoanSplit.splitThree(total)
        assertEquals(Money(103343), parts[0])
        assertEquals(Money(103343), parts[1])
        assertEquals(Money(103345), parts[2])
        assertEquals(total, parts.sum())
    }

    @Test
    fun findsTheStudentLoanCategoryByNameFirst() {
        val categories = listOf(
            Category(id = 1, name = "Bursary", flow = Flow.IN, forecastMode = ForecastMode.SCHEDULED_ONLY, isMajorIncomeDefault = true),
            Category(id = 2, name = "Student loan", flow = Flow.IN, forecastMode = ForecastMode.SCHEDULED_ONLY, isMajorIncomeDefault = true),
        )
        assertEquals(2L, LoanSplit.studentLoanCategory(categories)?.id)
    }

    @Test
    fun fallsBackToTheFirstMajorIncomeInCategory() {
        val categories = listOf(
            Category(id = 1, name = "Wages", flow = Flow.IN, forecastMode = ForecastMode.LEARNED, isMajorIncomeDefault = false),
            Category(id = 2, name = "Bursary", flow = Flow.IN, forecastMode = ForecastMode.SCHEDULED_ONLY, isMajorIncomeDefault = true),
        )
        assertEquals(2L, LoanSplit.studentLoanCategory(categories)?.id)
    }

    @Test
    fun returnsNullWhenNoneQualify() {
        val categories = listOf(
            Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED),
        )
        assertNull(LoanSplit.studentLoanCategory(categories))
    }
}
