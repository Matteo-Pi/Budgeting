package io.github.matteopi.budgeting.ui.quickadd

import io.github.matteopi.budgeting.core.model.Money

/**
 * Renders [money]'s magnitude as keypad-entry text ("10", "3.85") — the inverse of
 * `Money.parse`, used to pre-fill the amount when a preset is applied.
 */
internal fun moneyToEntryText(money: Money): String {
    val pence = money.abs().pence
    val pounds = pence / 100
    val remainder = pence % 100
    return if (remainder == 0L) pounds.toString() else "$pounds.${remainder.toString().padStart(2, '0')}"
}
