package io.github.matteopi.budgeting.ui.receipts

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.receipts.DraftItem
import io.github.matteopi.budgeting.core.receipts.ReceiptCheck
import io.github.matteopi.budgeting.core.receipts.ReceiptChecks
import io.github.matteopi.budgeting.core.receipts.ReceiptDraft
import io.github.matteopi.budgeting.core.receipts.ReceiptMatching
import io.github.matteopi.budgeting.core.receipts.ReceiptSource
import io.github.matteopi.budgeting.core.receipts.Store
import io.github.matteopi.budgeting.ui.activity.toWeekdayShortLabel
import java.time.LocalDate

/*
 * The editable state of the review screen and the pure rules derived from it (the check against the
 * total, the matching transaction, what blocks Save). Kept free of Android and of repositories so
 * [ReceiptReviewViewModel] only has to feed it data. See docs/PLAN.md §10.4.
 */

/** How the shop is named on the transactions a receipt is saved as ("Tesco", "Sainsbury's", ...). */
fun receiptStoreName(store: Store): String = when (store) {
    Store.TESCO -> "Tesco"
    Store.SAINSBURYS -> "Sainsbury's"
    Store.MORRISONS -> "Morrisons"
    Store.OTHER -> "Shop"
}

/** Where an item's category came from, which decides whether it is learned and whether "set for all unset" overrides it. */
enum class CategorySource {
    /** No category could be suggested. */
    NONE,

    /** The shop's most used category (or Groceries): a starting guess nobody has confirmed. */
    GUESS,

    /** The category of the product name learned from an earlier receipt. */
    ALIAS,

    /** Chosen by the user. */
    USER,
}

/**
 * One item row. Amounts are the text in their fields (so half-typed numbers survive); [price] is
 * the whole line's price before [discount], and [discount] is the (positive) saving, blank for none.
 * [rawName] is the line as printed; it is blank for items the user added.
 */
data class ReviewItem(
    val key: Long,
    val rawName: String,
    val name: String,
    val nameEdited: Boolean,
    val fromAlias: Boolean,
    val quantity: String,
    val price: String,
    val discount: String,
    val categoryId: Long?,
    val categorySource: CategorySource,
    val weightGrams: Int? = null,
    val unitPrice: Money? = null,
    val confidence: Float = 1f,
)

/** Everything the user can change on the review screen. */
data class ReviewEdit(
    val store: Store = Store.OTHER,
    val date: LocalDate,
    val totalText: String = "",
    val items: List<ReviewItem> = emptyList(),
    val selectedAccountId: Long? = null,
    /** True once the user picked an account, which restricts the matching transaction to that account. */
    val accountPinned: Boolean = false,
    val replace: Boolean = true,
    val source: ReceiptSource = ReceiptSource.PHOTO,
    val warnings: List<String> = emptyList(),
)

sealed interface ReviewPhase {
    data object Loading : ReviewPhase

    data class Failed(val message: String) : ReviewPhase

    data object Ready : ReviewPhase
}

/** What is wrong with, or worth a look in, one item row. */
data class ItemFlags(
    val quantityInvalid: Boolean = false,
    val priceInvalid: Boolean = false,
    val discountInvalid: Boolean = false,
    val nameBlank: Boolean = false,
    /** Low confidence while the items do not add up: the line to check first. */
    val suspect: Boolean = false,
) {
    val highlighted: Boolean get() = quantityInvalid || priceInvalid || discountInvalid || nameBlank || suspect
}

/** The existing transaction a receipt would replace, described for the screen. */
data class ReceiptMatchInfo(val transactionId: Long, val accountId: Long, val description: String)

/** Values worked out from [ReviewEdit] and the stored accounts, categories and transactions. */
data class ReviewDerived(
    val accounts: List<Account> = emptyList(),
    /** Categories an item can have: OUT, not archived. */
    val categories: List<Category> = emptyList(),
    /** The account the transactions will be created in. */
    val accountId: Long? = null,
    val check: ReceiptCheck? = null,
    val checkMessage: String = "",
    val mismatch: Boolean = false,
    val match: ReceiptMatchInfo? = null,
    val itemFlags: Map<Long, ItemFlags> = emptyMap(),
    val hasUnsetCategories: Boolean = false,
    /** Why Save is disabled, or null when the receipt can be saved. */
    val problem: String? = "Add at least one item",
) {
    val canSave: Boolean get() = problem == null
}

object ReceiptReviewRules {

    /** Items below this confidence are highlighted when the items do not add up to the total. */
    const val LOW_CONFIDENCE = 0.85f

    fun parseQuantity(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it >= 1 }

    fun parsePrice(text: String): Money? = Money.parse(text)?.takeIf { !it.isNegative }

    /** A blank discount means none; otherwise a non-negative amount (the saving, not signed). */
    fun parseDiscount(text: String): Money? =
        if (text.isBlank()) Money.ZERO else Money.parse(text)?.takeIf { !it.isNegative }

    fun parseTotal(text: String): Money? = Money.parse(text)?.takeIf { it.isPositive }

    /** The categories an item can have, in the order the pickers show them. */
    fun itemCategories(categories: List<Category>): List<Category> =
        categories.filter { it.flow == CategoryFlow.OUT && !it.archived }

    /** The draft the core checks and matching work on, from what is currently typed (bad amounts count as £0). */
    fun toDraft(edit: ReviewEdit): ReceiptDraft = ReceiptDraft(
        store = edit.store,
        date = edit.date,
        items = edit.items.map { item ->
            DraftItem(
                rawName = item.rawName.ifBlank { item.name },
                quantity = parseQuantity(item.quantity) ?: 1,
                weightGrams = item.weightGrams,
                unitPrice = item.unitPrice,
                price = parsePrice(item.price) ?: Money.ZERO,
                discount = -(parseDiscount(item.discount) ?: Money.ZERO),
                confidence = item.confidence,
            )
        },
        total = parseTotal(edit.totalText),
        source = edit.source,
        warnings = edit.warnings,
    )

    fun checkMessage(check: ReceiptCheck): String = when (check) {
        ReceiptCheck.Matches -> "Items add up to the total ✓"
        ReceiptCheck.NoTotal -> "Enter the receipt total to check the items"
        is ReceiptCheck.Mismatch -> {
            val more = check.difference.isPositive
            "Items are ${check.difference.abs().format()} ${if (more) "more" else "less"} than the total" +
                " — check the highlighted lines"
        }
    }

    fun itemFlags(item: ReviewItem, mismatch: Boolean): ItemFlags = ItemFlags(
        quantityInvalid = parseQuantity(item.quantity) == null,
        priceInvalid = parsePrice(item.price) == null,
        discountInvalid = parseDiscount(item.discount) == null,
        nameBlank = item.name.isBlank(),
        suspect = mismatch && item.confidence < LOW_CONFIDENCE,
    )

    /** The first reason the receipt cannot be saved yet, or null. */
    fun problem(edit: ReviewEdit, flags: Map<Long, ItemFlags>, accountId: Long?): String? = when {
        edit.items.isEmpty() -> "Add at least one item"
        parseTotal(edit.totalText) == null -> "Enter the receipt total"
        edit.items.any { flags[it.key]?.nameBlank == true } -> "Every item needs a name"
        edit.items.any {
            val f = flags[it.key]
            f != null && (f.quantityInvalid || f.priceInvalid || f.discountInvalid)
        } -> "Fix the amounts marked in red"
        edit.items.any { it.categoryId == null } -> "Pick a category for every item"
        accountId == null -> "Pick an account"
        else -> null
    }

    /**
     * Works out everything the screen shows beyond the raw fields. The matching transaction is
     * found among [transactions] that are not already part of a split receipt; once the user has
     * picked an account only that account's transactions are considered. When a match exists and
     * the receipt replaces it, the new transactions go in the match's own account, so balances
     * stay exactly as they were.
     */
    fun derive(
        edit: ReviewEdit,
        accounts: List<Account>,
        categories: List<Category>,
        transactions: List<Transaction>,
    ): ReviewDerived {
        val draft = toDraft(edit)
        val check = ReceiptChecks.check(draft)
        val mismatch = check is ReceiptCheck.Mismatch

        val candidates = transactions.filter { tx ->
            tx.receiptId == null &&
                (!edit.accountPinned || edit.selectedAccountId == null || tx.accountId == edit.selectedAccountId)
        }
        val matched = ReceiptMatching.findTransaction(draft, candidates, edit.selectedAccountId)
        val match = matched?.let { tx ->
            val category = categories.firstOrNull { it.id == tx.categoryId }
            val title = tx.label.ifBlank { category?.name ?: "transaction" }
            val where = if (accounts.size > 1) accounts.firstOrNull { it.id == tx.accountId }?.let { " (${it.name})" }.orEmpty() else ""
            ReceiptMatchInfo(
                transactionId = tx.id,
                accountId = tx.accountId,
                description = "$title ${tx.amount.format()} on ${tx.date.toWeekdayShortLabel()}$where",
            )
        }

        val accountId = if (edit.replace && match != null) match.accountId else edit.selectedAccountId
        val flags = edit.items.associate { it.key to itemFlags(it, mismatch) }
        return ReviewDerived(
            accounts = accounts,
            categories = itemCategories(categories),
            accountId = accountId,
            check = check,
            checkMessage = checkMessage(check),
            mismatch = mismatch,
            match = match,
            itemFlags = flags,
            hasUnsetCategories = edit.items.any {
                it.categorySource == CategorySource.NONE || it.categorySource == CategorySource.GUESS
            },
            problem = problem(edit, flags, accountId),
        )
    }
}
