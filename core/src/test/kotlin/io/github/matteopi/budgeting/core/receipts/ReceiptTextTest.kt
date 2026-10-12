package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptTextTest {

    private fun amount(token: String) = ReceiptText.parseAmount(token)

    @Test
    fun `plain amounts`() {
        assertEquals(Money(125), amount("1.25")?.amount)
        assertEquals(Money(125), amount("£1.25")?.amount)
        assertEquals(Money(5), amount("0.05")?.amount)
        assertEquals(Money(125), amount("1,25")?.amount)
        assertEquals(Money(123450), amount("1,234.50")?.amount)
        assertFalse(amount("1.25")!!.ocrFixed)
    }

    @Test
    fun `negative amounts with the sign before or after`() {
        assertEquals(Money(-50), amount("-0.50")?.amount)
        assertEquals(Money(-50), amount("0.50-")?.amount)
        assertEquals(Money(-50), amount("-£0.50")?.amount)
        assertEquals(Money(-50), amount("£-0.50")?.amount)
    }

    @Test
    fun `ocr look alikes are read as digits and flagged`() {
        assertEquals(Money(100), amount("1.OO")?.amount)
        assertEquals(Money(145), amount("£l.45")?.amount)
        assertEquals(Money(145), amount("I.45")?.amount)
        assertEquals(Money(550), amount("S.5O")?.amount)
        assertEquals(Money(88), amount("0.B8")?.amount)
        assertTrue(amount("1.OO")!!.ocrFixed)
    }

    @Test
    fun `not an amount`() {
        assertNull(amount("abc"))
        assertNull(amount("12"))
        assertNull(amount("1.2"))
        assertNull(amount("O.O.O"))
    }

    @Test
    fun `trailing amount takes the name, the sign and an optional code letter`() {
        val a = ReceiptText.trailingAmount("MILK 2PT   £1.45 A")!!
        assertEquals("MILK 2PT", a.name)
        assertEquals(Money(145), a.amount)
        assertEquals(Money(145), ReceiptText.trailingAmount("MILK 1.45 *")?.amount)
        assertEquals(Money(145), ReceiptText.trailingAmount("MILK 1.45")?.amount)
        assertEquals(Money(-30), ReceiptText.trailingAmount("CLUBCARD PRICE -£0.30")?.amount)
        assertEquals(Money(-30), ReceiptText.trailingAmount("CLUBCARD PRICE 0.30-")?.amount)
        assertEquals("", ReceiptText.trailingAmount("-0.30")?.name)
    }

    @Test
    fun `trailing amount ignores sizes and plain numbers`() {
        assertNull(ReceiptText.trailingAmount("COKE 1.5L"))
        assertNull(ReceiptText.trailingAmount("COKE 1.5l"))
        assertNull(ReceiptText.trailingAmount("MILK 2PT"))
        assertNull(ReceiptText.trailingAmount("MILK 145"))
        assertEquals(Money(85), ReceiptText.trailingAmount("WATER 1.5l   0.85")?.amount)
    }

    @Test
    fun `E in front of digits is a pound sign but a lone E stays in the name`() {
        assertEquals(Money(145), ReceiptText.trailingAmount("MILK E1.45")?.amount)
        val vitamin = ReceiptText.trailingAmount("VITAMIN E 3.50")!!
        assertEquals("VITAMIN E", vitamin.name)
        assertEquals(Money(350), vitamin.amount)
    }

    @Test
    fun `all amounts in a line`() {
        assertEquals(listOf(Money(110), Money(220)), ReceiptText.allAmounts("2 @ £1.10 £2.20").map { it.amount })
    }

    private fun date(text: String) = ReceiptDates.find(text)

    @Test
    fun `numeric date formats`() {
        val expected = LocalDate.of(2026, 10, 12)
        assertEquals(expected, date("12/10/2026"))
        assertEquals(expected, date("12/10/26"))
        assertEquals(expected, date("12-10-26"))
        assertEquals(expected, date("12-10-2026"))
        assertEquals(expected, date("12.10.2026"))
        assertEquals(expected, date("2026-10-12"))
        assertEquals(LocalDate.of(2026, 1, 5), date("5/1/26"))
    }

    @Test
    fun `text month formats`() {
        val expected = LocalDate.of(2026, 10, 12)
        assertEquals(expected, date("12 Oct 2026"))
        assertEquals(expected, date("12 OCTOBER 2026"))
        assertEquals(expected, date("12-Oct-26"))
        assertEquals(expected, date("12th Oct 2026".replace("th", "")))
        assertEquals(LocalDate.of(2026, 3, 1), date("1 Mar 2026"))
    }

    @Test
    fun `date and time on one line, with labels around`() {
        val expected = LocalDate.of(2026, 10, 12)
        assertEquals(expected, date("12/10/2026 14:32"))
        assertEquals(expected, date("Date: 12-10-26 Time: 09.14"))
        assertEquals(expected, date("Tue 12 Oct 2026 14:32:05"))
        assertEquals(expected, date("14:32 12/10/26 TILL 4"))
    }

    @Test
    fun `ocr confusions in a date are forgiven`() {
        assertEquals(LocalDate.of(2026, 10, 12), date("12/1O/2O26"))
        assertEquals(LocalDate.of(2026, 10, 12), date("l2/10/26"))
    }

    @Test
    fun `invalid or missing dates give null`() {
        assertNull(date("31/02/2026"))
        assertNull(date("MILK 1.45"))
        assertNull(date("14:32:05"))
        assertNull(date("12/10/1926"))
        assertNull(date("Total 12.84"))
    }
}
