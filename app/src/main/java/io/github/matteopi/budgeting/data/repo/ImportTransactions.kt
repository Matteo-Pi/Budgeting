package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate

/** Turns one selected statement row into the transactions the import inserts for it (docs/PLAN.md §5.16, §6.5). */
object ImportTransactions {

    /**
     * A NORMAL transaction for an ordinary row. For a move between the user's accounts
     * ([SelectedImportRow.transfer]), a TRANSFER leg on [accountId] with no category (same import key and
     * batch as any other imported row) sharing [transferGroupId] with the opposite leg on the other account,
     * when there is one: the negated amount, the same date and label, no import key (it is not a row of that
     * account's statement) but the same batch, so undoing the batch removes it. The opposite leg is left out
     * when it is dated before [otherAccountOpeningDate] (that account's opening balance already includes it).
     * Returns the transactions in insertion order.
     */
    fun forRow(
        accountId: Long,
        selected: SelectedImportRow,
        batchId: Long,
        nowMillis: Long,
        transferGroupId: String,
        otherAccountOpeningDate: LocalDate?,
    ): List<Transaction> {
        val row = selected.row
        val transfer = selected.transfer
        if (transfer == null) {
            return listOf(
                Transaction(
                    accountId = accountId,
                    amount = row.amount,
                    date = row.date,
                    createdAtEpochMillis = nowMillis,
                    categoryId = selected.categoryId,
                    label = row.description,
                    kind = TxKind.NORMAL,
                    importBatchId = batchId,
                    importKey = selected.importKey,
                ),
            )
        }

        val own = Transaction(
            accountId = accountId,
            amount = row.amount,
            date = row.date,
            createdAtEpochMillis = nowMillis,
            categoryId = null,
            label = row.description,
            kind = TxKind.TRANSFER,
            transferGroupId = transferGroupId,
            importBatchId = batchId,
            importKey = selected.importKey,
        )
        val otherId = transfer.otherAccountId
        val alreadyInOpeningBalance = otherAccountOpeningDate != null && row.date.isBefore(otherAccountOpeningDate)
        if (otherId == null || otherId == accountId || alreadyInOpeningBalance) return listOf(own)
        return listOf(own, own.copy(accountId = otherId, amount = -row.amount, importKey = null))
    }
}
