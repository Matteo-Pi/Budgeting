package io.github.matteopi.budgeting.ui.quickadd

import io.github.matteopi.budgeting.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class AmountEntryFormatTest {
    @Test
    fun wholePoundsHaveNoDecimal() {
        assertEquals("10", moneyToEntryText(Money.pounds(10)))
    }

    @Test
    fun keepsPence() {
        assertEquals("3.85", moneyToEntryText(Money(385)))
    }

    @Test
    fun padsASinglePenceDigit() {
        assertEquals("3.05", moneyToEntryText(Money(305)))
    }

    @Test
    fun usesTheMagnitudeOfNegativeAmounts() {
        assertEquals("3.85", moneyToEntryText(Money(-385)))
    }

    @Test
    fun zeroIsZero() {
        assertEquals("0", moneyToEntryText(Money.ZERO))
    }
}
