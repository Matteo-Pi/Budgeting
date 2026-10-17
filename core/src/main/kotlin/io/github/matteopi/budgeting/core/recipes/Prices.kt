package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.receipts.PurchasedItem
import io.github.matteopi.budgeting.core.receipts.Store
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Where a price came from. */
sealed interface PriceSource {
    /** What was paid on a receipt, after discounts. */
    data class Receipt(val date: LocalDate, val store: Store) : PriceSource

    /** A price typed on the ingredient; [date] is when it was checked, if recorded. */
    data class Typed(val date: LocalDate?) : PriceSource

    val dateOrNull: LocalDate?
        get() = when (this) {
            is Receipt -> date
            is Typed -> date
        }
}

/**
 * An ingredient's price. [perUnitPence] is pence per gram or millilitre and is deliberately a
 * `Double` (docs/PLAN.md §11.2: per-gram prices are fractions of a penny). [packPrice] is what one
 * pack costs, rounded half up to the penny. [old] when the price is more than
 * [Prices.OLD_AFTER_DAYS] days old.
 */
data class ResolvedPrice(
    val perUnitPence: Double,
    val packPrice: Money,
    val source: PriceSource,
    val old: Boolean,
)

/** Which price an ingredient costs at (docs/PLAN.md §11.2, "Price resolution"). */
object Prices {

    /** A price older than this many days is marked old. */
    const val OLD_AFTER_DAYS = 120L

    /**
     * The latest price paid for [ingredient]'s linked product in [purchases], else its typed pack
     * price, else null.
     *
     * - From a receipt: the latest purchase of the product (by date, then input order) whose spend
     *   (price plus discount) is above zero. A weighed item (`weightGrams` set) gives pence per gram
     *   directly (1 g counted as 1 ml for a millilitre ingredient); otherwise spend ÷ quantity is one
     *   pack, divided by the ingredient's pack size.
     * - Else the typed pack price, divided by the pack size.
     * - [today] decides whether the price is old.
     */
    fun resolve(ingredient: Ingredient, purchases: List<PurchasedItem>, today: LocalDate): ResolvedPrice? {
        val productId = ingredient.productId
        if (productId != null) {
            val latest = latestPaid(purchases.filter { it.productId == productId })
            if (latest != null) fromReceipt(ingredient, latest, today)?.let { return it }
        }
        return typed(ingredient, today)
    }

    /** [resolve] for every ingredient, keyed by id; ingredients with no price are left out. */
    fun resolveAll(ingredients: List<Ingredient>, purchases: List<PurchasedItem>, today: LocalDate): Map<Long, ResolvedPrice> {
        val byProduct = purchases.groupBy { it.productId }
        val out = LinkedHashMap<Long, ResolvedPrice>()
        for (ingredient in ingredients) {
            val own = ingredient.productId?.let { byProduct[it] }.orEmpty()
            resolve(ingredient, own, today)?.let { out[ingredient.id] = it }
        }
        return out
    }

    private fun latestPaid(purchases: List<PurchasedItem>): PurchasedItem? {
        var best: PurchasedItem? = null
        for (p in purchases) {
            if (p.spend.pence <= 0) continue
            if (best == null || !p.date.isBefore(best.date)) best = p
        }
        return best
    }

    private fun fromReceipt(ingredient: Ingredient, purchase: PurchasedItem, today: LocalDate): ResolvedPrice? {
        if (ingredient.packSize <= 0.0) return null
        val spend = purchase.spend.pence.toDouble()
        val weight = purchase.weightGrams
        val perUnit: Double
        val pack: Double
        if (weight != null && weight > 0) {
            perUnit = spend / weight
            pack = perUnit * ingredient.packSize
        } else {
            val quantity = if (purchase.quantity > 0) purchase.quantity else 1
            pack = spend / quantity
            perUnit = pack / ingredient.packSize
        }
        return ResolvedPrice(
            perUnitPence = perUnit,
            packPrice = Money(Quantities.roundHalfUp(pack)),
            source = PriceSource.Receipt(purchase.date, purchase.store),
            old = isOld(purchase.date, today),
        )
    }

    private fun typed(ingredient: Ingredient, today: LocalDate): ResolvedPrice? {
        val price = ingredient.price ?: return null
        if (ingredient.packSize <= 0.0) return null
        return ResolvedPrice(
            perUnitPence = price.pence / ingredient.packSize,
            packPrice = price,
            source = PriceSource.Typed(ingredient.priceDate),
            old = ingredient.priceDate?.let { isOld(it, today) } ?: false,
        )
    }

    private fun isOld(date: LocalDate, today: LocalDate): Boolean = ChronoUnit.DAYS.between(date, today) > OLD_AFTER_DAYS
}
