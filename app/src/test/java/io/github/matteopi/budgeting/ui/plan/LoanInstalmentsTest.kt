package io.github.matteopi.budgeting.ui.plan

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoanInstalmentsTest {
    private val jan = LocalDate.of(2027, 1, 11)
    private val apr = LocalDate.of(2027, 4, 26)
    private val sep = LocalDate.of(2027, 9, 20)

    @Test
    fun `all three dates picked gives the dates in order`() {
        val instalments = listOf(LoanInstalment(jan), LoanInstalment(apr), LoanInstalment(sep))
        assertEquals(listOf(jan, apr, sep), LoanInstalments.datesOrNull(instalments))
    }

    @Test
    fun `any blank date means nothing can be saved`() {
        assertNull(LoanInstalments.datesOrNull(listOf(LoanInstalment(jan), LoanInstalment(apr), LoanInstalment(null))))
        assertNull(LoanInstalments.datesOrNull(listOf(LoanInstalment(null), LoanInstalment(null), LoanInstalment(null))))
    }

    @Test
    fun `an instalment starts with no amount typed`() {
        assertEquals("", LoanInstalment(null).amountText)
    }
}
