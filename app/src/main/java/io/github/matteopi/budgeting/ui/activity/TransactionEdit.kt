package io.github.matteopi.budgeting.ui.activity

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate

/**
 * What the user asked the edit sheet to do (docs/PLAN.md §6.5, "Editing"). The sheet only builds one of
 * these; [TransactionEditor] turns it into repository calls, so Activity, an account's page and its history
 * all edit the same way.
 */
sealed interface TransactionEdit {
    /** Save [original]'s own fields (a NORMAL transaction, an opening balance, a balance check). */
    data class Single(
        val original: Transaction,
        val amount: Money,
        val categoryId: Long?,
        val accountId: Long,
        val date: LocalDate,
        val label: String,
        val note: String,
    ) : TransactionEdit

    /**
     * Turn the NORMAL transaction [original] into a move between accounts, with these edits applied. The
     * opposite leg is created on [otherAccountId], or not at all when that is null ("Not in the app").
     */
    data class ToTransfer(
        val original: Transaction,
        val amount: Money,
        val accountId: Long,
        val date: LocalDate,
        val label: String,
        val note: String,
        val otherAccountId: Long?,
    ) : TransactionEdit

    /**
     * Edit a transfer: money leaves [fromAccountId] and arrives in [toAccountId] (null = an account that is not in
     * the app). [legId] is any one of its current rows; [amount] is a magnitude.
     */
    data class Transfer(
        val legId: Long,
        val fromAccountId: Long?,
        val toAccountId: Long?,
        val amount: Money,
        val date: LocalDate,
        val label: String,
    ) : TransactionEdit

    /** "Not a transfer": keep the leg [keepId] as a NORMAL transaction in [categoryId] and delete the other leg. */
    data class ToNormal(val keepId: Long, val categoryId: Long) : TransactionEdit

    /** Delete the transaction (both legs of a transfer). */
    data class Delete(val transactionId: Long) : TransactionEdit
}

/**
 * Asked after a NORMAL transaction's category changed and other NORMAL transactions share its merchant: change
 * them too? [merchantKey] is `MerchantKey.normalise` of the label, [count] how many others would change.
 */
data class SimilarPrompt(val merchantKey: String, val categoryId: Long, val exceptId: Long, val count: Int) {
    /** The dialog's question, e.g. `Change the other 7 "TESCO STORES" transactions to Groceries too?`. */
    fun question(categoryName: String): String =
        if (count == 1) {
            "Change the other \"$merchantKey\" transaction to $categoryName too?"
        } else {
            "Change the other $count \"$merchantKey\" transactions to $categoryName too?"
        }

    /** The text of the ticked-by-default checkbox. */
    fun rememberLabel(categoryName: String): String = "Use $categoryName for $merchantKey in future imports"
}

/** The small pure rules the edit sheet is built on. */
object EditRules {

    /** Which form the sheet shows for a row. */
    enum class EditorKind {
        /** An ordinary transaction (or a balance check): amount, direction, category, account, date, label, note. */
        SINGLE,

        /** An account's opening balance: a signed amount and a date, labelled "Starting balance". */
        STARTING_BALANCE,

        /** An opening-balance entry a statement import made to keep balances right: explained, not editable. */
        IMPORT_ENTRY,

        /** One or both legs of a move between accounts. */
        TRANSFER,
    }

    fun editorKind(row: ActivityRow): EditorKind = when (row) {
        is ActivityRow.Transfer -> EditorKind.TRANSFER
        is ActivityRow.Single -> {
            val tx = row.tx
            when {
                tx.kind == TxKind.TRANSFER -> EditorKind.TRANSFER
                tx.kind == TxKind.OPENING_BALANCE && tx.importBatchId != null -> EditorKind.IMPORT_ENTRY
                tx.kind == TxKind.OPENING_BALANCE -> EditorKind.STARTING_BALANCE
                else -> EditorKind.SINGLE
            }
        }
    }

    /** A NORMAL transaction that is not part of a receipt can become a move between accounts. */
    fun canMoveBetweenAccounts(tx: Transaction): Boolean = tx.kind == TxKind.NORMAL && tx.receiptId == null

    /** True for the per-category parts a saved receipt was split into. */
    fun isReceiptPart(tx: Transaction): Boolean = tx.receiptId != null

    /** The categories that can be picked for money going [out] or coming in: not archived, matching or both-ways. */
    fun eligibleCategories(categories: List<Category>, out: Boolean): List<Category> {
        val wanted = if (out) CategoryFlow.OUT else CategoryFlow.IN
        return categories.filter { !it.archived && (it.flow == wanted || it.flow == CategoryFlow.BOTH) }
    }

    /** The accounts that can sit at the other end of a move from [accountId]: every other account that is not archived. */
    fun otherAccountChoices(accounts: List<Account>, accountId: Long): List<Account> =
        accounts.filter { !it.archived && it.id != accountId }

    /**
     * The accounts offered for one side of a transfer: every account that is not archived, plus [current] when it
     * is archived (so the existing choice can still be shown).
     */
    fun transferAccountChoices(accounts: List<Account>, current: Long?): List<Account> =
        accounts.filter { !it.archived || it.id == current }

    /** Why a transfer's two sides can't be saved, or null when they can. */
    fun transferSidesError(fromAccountId: Long?, toAccountId: Long?): String? = when {
        fromAccountId == null && toAccountId == null -> "At least one side of a transfer must be an account in the app."
        fromAccountId == toAccountId -> "Pick two different accounts."
        else -> null
    }

    /** The one-line note on a receipt part's form. */
    const val RECEIPT_PART_NOTE = "Part of a receipt. Changing the amount makes it differ from the receipt."

    /** The note on a balance check's form. */
    const val BALANCE_CHECK_NOTE = "A balance check: the gap between the app and your bank. Changing it changes your balance history."

    /** The note on an opening balance's form. */
    const val STARTING_BALANCE_NOTE = "What the account held when it was added. Changing it changes every balance after it."

    /** What the sheet says about an opening-balance entry made by a statement import. */
    const val IMPORT_ENTRY_NOTE =
        "A statement import made this entry to keep your balance right. It changes or goes away when you undo the import."
}
