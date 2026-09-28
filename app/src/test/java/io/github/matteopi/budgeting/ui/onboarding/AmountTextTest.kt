package io.github.matteopi.budgeting.ui.onboarding

import io.github.matteopi.budgeting.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmountTextTest {
    @Test
    fun blankTextIsNotAnAmountButIsValid() {
        assertNull(AmountText.parsePositiveOrNull(""))
        assertNull(AmountText.parsePositiveOrNull("   "))
        assertTrue(AmountText.isValidOrBlank(""))
        assertTrue(AmountText.isValidOrBlank("   "))
    }

    @Test
    fun parsesAValidNonNegativeAmount() {
        assertEquals(Money.pounds(12), AmountText.parsePositiveOrNull("12"))
        assertEquals(Money(1250), AmountText.parsePositiveOrNull("12.50"))
        assertEquals(Money.ZERO, AmountText.parsePositiveOrNull("0"))
        assertTrue(AmountText.isValidOrBlank("12.50"))
    }

    @Test
    fun rejectsNegativeAmounts() {
        assertNull(AmountText.parsePositiveOrNull("-5"))
        assertFalse(AmountText.isValidOrBlank("-5"))
    }

    @Test
    fun rejectsUnparsableText() {
        assertNull(AmountText.parsePositiveOrNull("abc"))
        assertFalse(AmountText.isValidOrBlank("abc"))
    }
}
