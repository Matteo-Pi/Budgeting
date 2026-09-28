package io.github.matteopi.budgeting.ui.activity

import io.github.matteopi.budgeting.core.model.Money

/**
 * A locale-independent starting value for an editable amount field, e.g. "12.50" for
 * `Money.pounds(12) + Money(50)`, or "-12.50" for the same amount negated. Deliberately avoids
 * `String.format("%.2f", ...)`, whose decimal separator follows the device's default locale and
 * can disagree with [Money.parse].
 */
fun Money.toInputText(): String {
    val magnitude = abs()
    val whole = magnitude.pence / 100
    val fraction = (magnitude.pence % 100).toString().padStart(2, '0')
    val sign = if (isNegative) "-" else ""
    return "$sign$whole.$fraction"
}
