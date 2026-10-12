package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate

/**
 * One fragment of text as read from a receipt. OCR returns boxes, so fragments of one printed
 * line arrive separately; text sources (shared text, PDF text) give one fragment per line with
 * every position left at 0. Units are whatever the OCR uses (pixels); only differences matter.
 *
 * [bottom] is optional: 0 (or anything not below [top]) means "unknown", and the vertical
 * centre is then [top] itself.
 */
data class ReceiptLine(
    val text: String,
    val top: Float = 0f,
    val left: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

/** One printed line of a receipt, after fragments on the same baseline were merged. */
data class ReceiptRow(val text: String)

enum class Store { TESCO, SAINSBURYS, MORRISONS, OTHER }

enum class ReceiptSource { PHOTO, SHARED_TEXT, SHARED_PDF, DATA_EXPORT }

/**
 * One purchased line of a receipt.
 *
 * Sign conventions (the opposite of [io.github.matteopi.budgeting.core.model.Transaction.amount]):
 * - [price] is money spent and is positive: the amount printed on the item line, **before** any
 *   discount, for the whole line (so for `2 @ £1.10` it is £2.20, and for a weighed item the
 *   price of that weight).
 * - [discount] is zero or negative (e.g. a Clubcard Price of 50p is `Money(-50)`); the amount
 *   actually paid for the item is `price + discount`, see [spend].
 * - [unitPrice] is the price of one unit when the receipt says so ("2 @ £1.10" gives £1.10), or the
 *   price **per kilogram** for a weighed item ([weightGrams] set). Null when the receipt does not say.
 * - [confidence] runs from 0 to 1; low values mean the line (name or price) looked odd, for
 *   example the price contained OCR look-alike letters, and the review screen should highlight it.
 */
data class DraftItem(
    val rawName: String,
    val quantity: Int = 1,
    val weightGrams: Int? = null,
    val unitPrice: Money? = null,
    val price: Money,
    val discount: Money = Money.ZERO,
    val confidence: Float = 1f,
) {
    /** What was actually paid for this line: [price] plus the (negative) [discount]. */
    val spend: Money get() = price + discount
}

/**
 * The result of reading a receipt, before the user has reviewed it.
 *
 * [total] is the positive amount the receipt says was paid (null if none was found). [warnings]
 * are short British-English messages for the review screen.
 */
data class ReceiptDraft(
    val store: Store,
    val date: LocalDate?,
    val items: List<DraftItem>,
    val total: Money?,
    val source: ReceiptSource,
    val warnings: List<String> = emptyList(),
)
