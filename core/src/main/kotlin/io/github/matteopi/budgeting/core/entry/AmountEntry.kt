package io.github.matteopi.budgeting.core.entry

import io.github.matteopi.budgeting.core.model.Money

/** A key on the custom quick-add keypad. */
sealed class KeypadKey {
    data class Digit(val value: Int) : KeypadKey()
    data object Dot : KeypadKey()
    data object Backspace : KeypadKey()
    data object Clear : KeypadKey()
}

/**
 * The text state of the amount keypad, built up one key press at a time. Immutable: [press]
 * returns a new instance. See docs/PLAN.md §5.18.
 */
data class AmountEntry(val text: String = "") {

    fun press(key: KeypadKey): AmountEntry = when (key) {
        is KeypadKey.Digit -> pressDigit(key.value)
        KeypadKey.Dot -> pressDot()
        KeypadKey.Backspace -> AmountEntry(text.dropLast(1))
        KeypadKey.Clear -> AmountEntry("")
    }

    private fun pressDigit(digit: Int): AmountEntry {
        require(digit in 0..9) { "digit must be 0..9" }

        val dotIndex = text.indexOf('.')
        if (dotIndex >= 0) {
            val decimals = text.length - dotIndex - 1
            return if (decimals >= 2) this else AmountEntry(text + digit)
        }

        // No leading zeros: "0" followed by a non-zero digit replaces the zero, not appends to it.
        if (text == "0") {
            return if (digit == 0) this else AmountEntry(digit.toString())
        }

        if (text.length >= 6) return this
        return AmountEntry(text + digit)
    }

    private fun pressDot(): AmountEntry {
        if (text.contains('.')) return this
        return if (text.isEmpty()) AmountEntry("0.") else AmountEntry("$text.")
    }

    /** Null if empty or the amount is zero. */
    val money: Money?
        get() {
            if (text.isEmpty()) return null
            val parsed = Money.parse(text) ?: return null
            return if (parsed.isZero) null else parsed
        }

    /** "£0" when empty, otherwise "£" followed by what has been typed so far, e.g. "£12.5". */
    val display: String
        get() = if (text.isEmpty()) "£0" else "£$text"
}
