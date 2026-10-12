package io.github.matteopi.budgeting.core.model

import java.time.LocalDate

/*
 * Persisted receipt models (docs/PLAN.md §10.3). Store and source are plain strings (e.g. "TESCO",
 * "PHOTO") so that storage does not depend on the parsing code in `core/receipts/`.
 */

/** One shopping receipt. Its items are [ReceiptItem]s; its money lives in transactions. */
data class Receipt(
    val id: Long = 0,
    /** Store name key, e.g. "TESCO", "SAINSBURYS", "MORRISONS". */
    val store: String,
    val date: LocalDate,
    /** Positive: what was paid, after discounts. */
    val total: Money,
    /** Where the receipt came from, e.g. "PHOTO", "EMAIL", "EXPORT". */
    val source: String,
    /**
     * The first transaction created or matched when the receipt was saved. Informational only:
     * the transactions that belong to the receipt are found through `Transaction.receiptId`.
     */
    val transactionId: Long? = null,
    val createdAtEpochMillis: Long = 0,
)

data class ReceiptItem(
    val id: Long = 0,
    val receiptId: Long,
    val productId: Long? = null,
    /** The line exactly as printed, e.g. "TS SEMI SKMD MLK 2PT". */
    val rawName: String,
    val quantity: Int = 1,
    /** Set for weighed items. */
    val weightGrams: Int? = null,
    val unitPrice: Money? = null,
    /** Positive spend for this line before [discount]. */
    val price: Money,
    /** Zero or negative: a Clubcard / Nectar / multibuy saving attached to this line. */
    val discount: Money = Money.ZERO,
    val categoryId: Long? = null,
)

/** A named thing the user buys ("Semi-skimmed milk 2 pints"), learned from receipt lines. */
data class Product(
    val id: Long = 0,
    val name: String,
    val categoryId: Long? = null,
)

/** Maps one store's printed name (normalised to [rawNameKey]) to a [Product]. */
data class ProductAliasRecord(
    val id: Long = 0,
    val store: String,
    val rawNameKey: String,
    val productId: Long,
)
