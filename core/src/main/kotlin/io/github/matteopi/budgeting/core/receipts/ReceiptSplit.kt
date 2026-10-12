package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money

/**
 * One reviewed receipt item for splitting. [price] is positive (money spent) and [discount] zero
 * or negative, as in [DraftItem]; [categoryId] null = not categorised.
 */
data class SplitItem(
    val categoryId: Long?,
    val price: Money,
    val discount: Money = Money.ZERO,
) {
    val spend: Money get() = price + discount
}

/** One transaction of a split shop: [amount] is signed like a transaction (negative = spending). */
data class SplitPart(val categoryId: Long?, val amount: Money)

object ReceiptSplit {

    /**
     * Groups item spend (price + discount) per category and returns one signed amount per group
     * (negative = spending) that sums **exactly** to minus [total], the positive receipt total.
     *
     * The receipt total is the truth: any difference from the item sum (rounding, or something
     * the items do not list such as a bag charge) goes on the largest group. With no items at
     * all, or only uncategorised ones, it lands in the uncategorised (null) group, so the result
     * is never empty. Groups whose final amount is zero are dropped (unless all are). Result is
     * ordered largest first; ties keep the order in which the categories first appear in [items].
     */
    fun byCategory(items: List<SplitItem>, total: Money): List<SplitPart> {
        val spend = LinkedHashMap<Long?, Long>()
        for (item in items) spend[item.categoryId] = (spend[item.categoryId] ?: 0L) + item.spend.pence
        if (spend.isEmpty()) return listOf(SplitPart(null, -total))

        val groups = spend.entries.map { it.key to it.value }.toMutableList()
        val difference = total.pence - groups.sumOf { it.second }
        if (difference != 0L) {
            // Largest by spend; a categorised group wins a tie with the uncategorised one.
            val index = groups.indices.maxWithOrNull(
                compareBy<Int>({ groups[it].second }, { if (groups[it].first != null) 1 else 0 }, { -it }),
            )!!
            groups[index] = groups[index].first to groups[index].second + difference
        }
        val nonZero = groups.filter { it.second != 0L }
        val kept = nonZero.ifEmpty { listOf(groups.first()) }
        return kept
            .sortedByDescending { it.second }
            .map { SplitPart(it.first, Money(-it.second)) }
    }
}
