package io.github.matteopi.budgeting.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {
    @Test
    fun formats() {
        assertEquals("£0.00", Money.ZERO.format())
        assertEquals("£3.20", Money(320).format())
        assertEquals("-£3.20", Money(-320).format())
        assertEquals("£1,234.56", Money(123456).format())
        assertEquals("£1,000,000.00", Money(100_000_000).format())
        assertEquals("+£5.00", Money(500).format(showPlus = true))
        assertEquals("£1,234", Money(123456).format(showPence = false))
    }

    @Test
    fun parses() {
        assertEquals(Money(320), Money.parse("3.2"))
        assertEquals(Money(320), Money.parse("3.20"))
        assertEquals(Money(300), Money.parse("3"))
        assertEquals(Money(50), Money.parse(".5"))
        assertEquals(Money(123450), Money.parse("£1,234.5"))
        assertEquals(Money(-300), Money.parse("-3"))
        assertEquals(Money(-300), Money.parse("-£3"))
        assertEquals(Money(1200), Money.parse(" 12 "))
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse("1.2.3"))
    }

    @Test
    fun scalesAndSums() {
        assertEquals(Money(150), Money(100).scale(1.5))
        assertEquals(Money(600), listOf(Money(100), Money(200), Money(300)).sum())
    }
}
