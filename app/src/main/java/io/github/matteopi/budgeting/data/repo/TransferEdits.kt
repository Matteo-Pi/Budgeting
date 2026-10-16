package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate

/**
 * The pure rules for editing transfers (docs/PLAN.md §6.5, "Editing"): which row is which side of a
 * transfer, and exactly which rows an edit inserts, updates and deletes. [TransactionRepository] applies
 * the results inside one database transaction; keeping the rules here makes them testable on the plain JVM.
 */
object TransferEdits {

    /**
     * The two sides of one transfer. [outgoing] is the leg where money leaves (negative), [incoming] the leg
     * where it arrives. Either is null when that side is an account that is not in the app (a lone leg).
     */
    data class Sides(val outgoing: Transaction?, val incoming: Transaction?) {
        /** The legs that exist, outgoing first. */
        val legs: List<Transaction> get() = listOfNotNull(outgoing, incoming)
    }

    /** What an edit does to the database: rows to [updates], to [inserts] (id 0) and to delete by id. */
    data class Plan(val updates: List<Transaction>, val inserts: List<Transaction>, val deletes: List<Long>)

    /**
     * Sorts the rows of one transfer group into its two sides. Two legs pair like the Activity list does (the first
     * negative one is the outgoing side); a lone leg is outgoing when it is negative and incoming otherwise.
     */
    fun sides(legs: List<Transaction>): Sides {
        if (legs.isEmpty()) return Sides(null, null)
        if (legs.size == 1) {
            val only = legs.single()
            return if (only.amount.isNegative) Sides(only, null) else Sides(null, only)
        }
        val outgoing = legs.firstOrNull { it.amount.isNegative } ?: legs.first()
        val incoming = legs.first { it.id != outgoing.id }
        return Sides(outgoing, incoming)
    }

    /**
     * The rows that turn the NORMAL transaction [edited] (the stored row with the user's edits applied) into one
     * leg of a transfer: first the leg itself (kind TRANSFER, no category, group [groupId]), then, when
     * [otherAccountId] names another account, the opposite leg there (negated amount, same date and label, no
     * import key). The opposite leg keeps the import batch, so undoing the import removes both.
     */
    fun toTransfer(edited: Transaction, otherAccountId: Long?, groupId: String, nowMillis: Long): List<Transaction> {
        val own = edited.copy(kind = TxKind.TRANSFER, categoryId = null, transferGroupId = groupId)
        val opposite = otherAccountId?.takeIf { it != edited.accountId }?.let { otherId ->
            freshLeg(own, otherId, -own.amount, own.date, own.label, groupId, nowMillis)
        }
        return listOfNotNull(own, opposite)
    }

    /**
     * The rows for editing a transfer so that money leaves [fromAccountId] and arrives in [toAccountId] (null =
     * an account that is not in the app, which has no leg). [legs] are the transfer's current rows. Existing
     * sides are updated in place (keeping their ids, notes and import keys), a side that had no leg gets a new
     * one, and a side now null loses its leg. Both legs always carry [amount] (as a magnitude), [date] and [label].
     */
    fun plan(
        legs: List<Transaction>,
        fromAccountId: Long?,
        toAccountId: Long?,
        amount: Money,
        date: LocalDate,
        label: String,
        groupId: String,
        nowMillis: Long,
    ): Plan {
        require(fromAccountId != null || toAccountId != null) { "A transfer needs at least one account in the app" }
        require(fromAccountId == null || fromAccountId != toAccountId) { "A transfer needs two different accounts" }
        val magnitude = amount.abs()
        val sides = sides(legs)
        val template = sides.outgoing ?: sides.incoming ?: error("A transfer has no legs to edit")
        val updates = mutableListOf<Transaction>()
        val inserts = mutableListOf<Transaction>()
        val deletes = mutableListOf<Long>()

        fun side(existing: Transaction?, accountId: Long?, signed: Money) {
            when {
                accountId == null -> if (existing != null) deletes += existing.id
                existing != null -> updates += existing.copy(
                    accountId = accountId,
                    amount = signed,
                    date = date,
                    label = label,
                    transferGroupId = groupId,
                )
                else -> inserts += freshLeg(template, accountId, signed, date, label, groupId, nowMillis)
            }
        }
        side(sides.outgoing, fromAccountId, -magnitude)
        side(sides.incoming, toAccountId, magnitude)
        return Plan(updates = updates, inserts = inserts, deletes = deletes)
    }

    /** [keep] as an ordinary transaction in [categoryId]: no longer a transfer leg. Its other leg is deleted by the caller. */
    fun toNormal(keep: Transaction, categoryId: Long): Transaction =
        keep.copy(kind = TxKind.NORMAL, categoryId = categoryId, transferGroupId = null)

    /**
     * A new leg modelled on [template]: same import batch (so undoing an import takes it too), but none of the
     * things that belong to one specific row: id, import key, receipt, recurring rule, scheduled item, note.
     */
    private fun freshLeg(
        template: Transaction,
        accountId: Long,
        amount: Money,
        date: LocalDate,
        label: String,
        groupId: String,
        nowMillis: Long,
    ): Transaction = template.copy(
        id = 0,
        accountId = accountId,
        amount = amount,
        date = date,
        createdAtEpochMillis = nowMillis,
        categoryId = null,
        label = label,
        note = "",
        kind = TxKind.TRANSFER,
        transferGroupId = groupId,
        recurringRuleId = null,
        scheduledItemId = null,
        importKey = null,
        receiptId = null,
    )
}
