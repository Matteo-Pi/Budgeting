package io.github.matteopi.budgeting.ui.quickadd

import io.github.matteopi.budgeting.core.entry.KeypadKey

/** Maps [io.github.matteopi.budgeting.ui.components.AmountKeypad]'s raw key strings to [KeypadKey]. */
object KeypadMapping {
    fun toKeypadKey(raw: String): KeypadKey? = when {
        raw == "." -> KeypadKey.Dot
        raw == "back" -> KeypadKey.Backspace
        raw.length == 1 && raw[0].isDigit() -> KeypadKey.Digit(raw[0] - '0')
        else -> null
    }
}
