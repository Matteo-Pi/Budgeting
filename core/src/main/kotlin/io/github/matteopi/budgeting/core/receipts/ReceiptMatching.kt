package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object ReceiptMatching {

    const val MAX_DAY_GAP = 2L

    /**
     * The existing NORMAL transaction that probably is this shop: amount equal to minus the
     * receipt total (or within 1p of it) and dated within +-2 days of the receipt date.
     * Preference: same day first, then [accountId] (when given), then the smallest day gap, then
     * the smallest amount difference, then the lowest id. Null if nothing qualifies, or the draft
     * has no total (or a non-positive one) or no date.
     */
    fun findTransaction(draft: ReceiptDraft, transactions: List<Transaction>, accountId: Long? = null): Transaction? {
        val total = draft.total ?: return null
        val date = draft.date ?: return null
        if (total.pence <= 0) return null
        val expected = -total.pence

        return transactions
            .filter { it.kind == TxKind.NORMAL }
            .filter { abs(it.amount.pence - expected) <= 1 }
            .map { it to abs(ChronoUnit.DAYS.between(date, it.date)) }
            .filter { it.second <= MAX_DAY_GAP }
            .minWithOrNull(
                compareBy<Pair<Transaction, Long>>(
                    { if (it.second == 0L) 0 else 1 },
                    { if (accountId != null && it.first.accountId == accountId) 0 else 1 },
                    { it.second },
                    { abs(it.first.amount.pence - expected) },
                    { it.first.id },
                ),
            )?.first
    }
}
