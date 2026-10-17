package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate

/**
 * One saved receipt item that has been matched to a product. Same sign conventions as
 * [DraftItem]: [price] positive (the whole line, before discount), [discount] zero or negative.
 * [unitPrice] is the shelf price of one unit (per kilogram for weighed items) when the receipt
 * said so; otherwise it is derived as [price] / [quantity]. [weightGrams] is set for weighed items
 * (the recipes section turns it into a price per gram).
 */
data class PurchasedItem(
    val productId: Long,
    val date: LocalDate,
    val store: Store,
    val quantity: Int = 1,
    val unitPrice: Money? = null,
    val price: Money,
    val discount: Money = Money.ZERO,
    val weightGrams: Int? = null,
) {
    /** The price of one unit: [unitPrice], else [price] divided by [quantity] rounded to the nearest penny. */
    val unit: Money
        get() = unitPrice ?: if (quantity > 1) {
            Money(Math.floorDiv(price.pence * 2 + quantity, quantity * 2L))
        } else {
            price
        }

    /** What was actually paid for the line: [price] plus the (negative) [discount]. */
    val spend: Money get() = price + discount
}

data class PricePoint(val date: LocalDate, val store: Store, val unitPrice: Money)

/** The latest recorded unit price against the one before it. */
data class PriceChange(val previous: PricePoint, val latest: PricePoint) {
    /** Latest minus previous: positive = it got dearer. */
    val difference: Money get() = latest.unitPrice - previous.unitPrice

    /** Change as a percentage of the previous price; null if the previous price was zero. */
    val percent: Double?
        get() = if (previous.unitPrice.isZero) null else difference.pence * 100.0 / previous.unitPrice.pence
}

data class ProductPriceHistory(
    val productId: Long,
    /** Oldest first; purchases on one date keep their input order. */
    val points: List<PricePoint>,
    /** Null with fewer than two points. */
    val change: PriceChange?,
) {
    val latest: PricePoint? get() = points.lastOrNull()
}

data class ProductStats(
    val productId: Long,
    /** Total units bought (sum of quantities). */
    val quantity: Int,
    /** Number of item records, i.e. times it appeared on a receipt. */
    val purchases: Int,
    /** Σ price + discount. */
    val spend: Money,
    val lastBought: LocalDate,
)

enum class MostBoughtBy { QUANTITY, PURCHASES, SPEND }

object ItemPrices {

    /** Unit price per purchase of [productId], oldest first; only [store] if given. */
    fun history(items: List<PurchasedItem>, productId: Long, store: Store? = null): List<PricePoint> =
        items.filter { it.productId == productId && (store == null || it.store == store) }
            .sortedBy { it.date }
            .map { PricePoint(it.date, it.store, it.unit) }

    /** The latest unit price against the one before it; null with fewer than two points. */
    fun change(points: List<PricePoint>): PriceChange? =
        if (points.size < 2) null else PriceChange(points[points.size - 2], points.last())

    /** Price history and latest change for every product, ordered by product id. */
    fun histories(items: List<PurchasedItem>): List<ProductPriceHistory> =
        items.map { it.productId }.distinct().sorted().map { id ->
            val points = history(items, id)
            ProductPriceHistory(id, points, change(points))
        }

    /** Products whose latest unit price differs from the previous one, biggest relative rise first. */
    fun priceChanges(items: List<PurchasedItem>): List<Pair<Long, PriceChange>> =
        histories(items)
            .mapNotNull { h -> h.change?.takeIf { !it.difference.isZero }?.let { h.productId to it } }
            .sortedByDescending { it.second.percent ?: 0.0 }

    /** Total spend (price + discount) on [productId], or on everything when null, from..to inclusive (null = unbounded). */
    fun spend(items: List<PurchasedItem>, productId: Long? = null, from: LocalDate? = null, to: LocalDate? = null): Money =
        Money(inPeriod(items, from, to).filter { productId == null || it.productId == productId }.sumOf { it.spend.pence })

    /** Spend per product over the period, highest first (ties by product id). */
    fun spendByProduct(items: List<PurchasedItem>, from: LocalDate? = null, to: LocalDate? = null): List<Pair<Long, Money>> =
        inPeriod(items, from, to)
            .groupBy { it.productId }
            .map { (id, list) -> id to Money(list.sumOf { it.spend.pence }) }
            .sortedWith(compareByDescending<Pair<Long, Money>> { it.second }.thenBy { it.first })

    /**
     * The most bought products over the period, best first. [by] picks the ranking; ties fall
     * back to the other measures and then the product id, so the order is deterministic.
     */
    fun mostBought(
        items: List<PurchasedItem>,
        limit: Int = 10,
        by: MostBoughtBy = MostBoughtBy.QUANTITY,
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): List<ProductStats> {
        val stats = inPeriod(items, from, to).groupBy { it.productId }.map { (id, list) ->
            ProductStats(
                productId = id,
                quantity = list.sumOf { it.quantity },
                purchases = list.size,
                spend = Money(list.sumOf { it.spend.pence }),
                lastBought = list.maxOf { it.date },
            )
        }
        val primary: Comparator<ProductStats> = when (by) {
            MostBoughtBy.QUANTITY -> compareByDescending<ProductStats> { it.quantity }.thenByDescending { it.purchases }
            MostBoughtBy.PURCHASES -> compareByDescending<ProductStats> { it.purchases }.thenByDescending { it.quantity }
            MostBoughtBy.SPEND -> compareByDescending<ProductStats> { it.spend }.thenByDescending { it.purchases }
        }
        return stats.sortedWith(primary.thenByDescending { it.spend }.thenBy { it.productId }).take(limit)
    }

    private fun inPeriod(items: List<PurchasedItem>, from: LocalDate?, to: LocalDate?) =
        items.filter { (from == null || !it.date.isBefore(from)) && (to == null || !it.date.isAfter(to)) }
}
