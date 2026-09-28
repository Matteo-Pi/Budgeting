package io.github.matteopi.budgeting.core.entry

import io.github.matteopi.budgeting.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountEntryTest {

    private fun type(vararg keys: KeypadKey): AmountEntry =
        keys.fold(AmountEntry()) { entry, key -> entry.press(key) }

    private fun digits(s: String): List<KeypadKey> = s.map { KeypadKey.Digit(it - '0') }

    @Test
    fun `empty entry displays and evaluates as zero`() {
        val entry = AmountEntry()
        assertEquals("£0", entry.display)
        assertNull(entry.money)
    }

    @Test
    fun `typing digits builds up whole pounds`() {
        val entry = type(*digits("125").toTypedArray())
        assertEquals("125", entry.text)
        assertEquals("£125", entry.display)
        assertEquals(Money.pounds(125), entry.money)
    }

    @Test
    fun `dot then digits gives pence`() {
        val entry = type(*digits("12").toTypedArray(), KeypadKey.Dot, *digits("5").toTypedArray())
        assertEquals("£12.5", entry.display)
        assertEquals(Money(1250), entry.money)
    }

    @Test
    fun `dot pressed first becomes zero point`() {
        val entry = type(KeypadKey.Dot)
        assertEquals("0.", entry.text)
        assertEquals("£0.", entry.display)
        assertNull(entry.money)
    }

    @Test
    fun `at most two decimal places`() {
        val entry = type(*digits("1").toTypedArray(), KeypadKey.Dot, *digits("2345").toTypedArray())
        assertEquals("1.23", entry.text)
        assertEquals(Money(123), entry.money)
    }

    @Test
    fun `at most six integer digits`() {
        val entry = type(*digits("123456789").toTypedArray())
        assertEquals("123456", entry.text)
    }

    @Test
    fun `only one dot allowed`() {
        val entry = type(*digits("1").toTypedArray(), KeypadKey.Dot, KeypadKey.Dot, *digits("5").toTypedArray())
        assertEquals("1.5", entry.text)
    }

    @Test
    fun `no leading zeros`() {
        val entry = type(KeypadKey.Digit(0), KeypadKey.Digit(5))
        assertEquals("5", entry.text)
    }

    @Test
    fun `repeated zero stays a single zero`() {
        val entry = type(KeypadKey.Digit(0), KeypadKey.Digit(0))
        assertEquals("0", entry.text)
        assertNull(entry.money)
    }

    @Test
    fun `zero dot digits is a valid decimal`() {
        val entry = type(KeypadKey.Digit(0), KeypadKey.Dot, KeypadKey.Digit(9), KeypadKey.Digit(9))
        assertEquals("0.99", entry.text)
        assertEquals(Money(99), entry.money)
    }

    @Test
    fun `backspace removes the last character`() {
        val entry = type(*digits("12").toTypedArray(), KeypadKey.Dot, KeypadKey.Digit(5), KeypadKey.Backspace)
        assertEquals("12.", entry.text)
    }

    @Test
    fun `clear resets to empty`() {
        val entry = type(*digits("999").toTypedArray(), KeypadKey.Clear)
        assertEquals("", entry.text)
        assertEquals("£0", entry.display)
    }

    @Test
    fun `zero amount is null money`() {
        val entry = type(KeypadKey.Digit(0), KeypadKey.Dot, KeypadKey.Digit(0), KeypadKey.Digit(0))
        assertNull(entry.money)
    }
}
