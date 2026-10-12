package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money

sealed interface ReceiptCheck {
    /** The items (with discounts) add up to the receipt total exactly. */
    data object Matches : ReceiptCheck

    /** [difference] = items total minus receipt total: positive means the items add up to too much. */
    data class Mismatch(val difference: Money) : ReceiptCheck

    /** The draft has no total to compare with. */
    data object NoTotal : ReceiptCheck
}

object ReceiptChecks {

    /** Σ price + Σ discount over all items (discounts are negative). */
    fun itemsTotal(draft: ReceiptDraft): Money =
        Money(draft.items.sumOf { it.price.pence + it.discount.pence })

    /**
     * Compares [itemsTotal] with the draft's total. A mismatch usually means an OCR misread, a
     * missed item or a discount that was not attached; the review screen highlights it.
     */
    fun check(draft: ReceiptDraft): ReceiptCheck {
        val total = draft.total ?: return ReceiptCheck.NoTotal
        val difference = itemsTotal(draft) - total
        return if (difference.isZero) ReceiptCheck.Matches else ReceiptCheck.Mismatch(difference)
    }
}
