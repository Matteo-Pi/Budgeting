package io.github.matteopi.budgeting.ui.insights

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.receipts.ItemPrices
import io.github.matteopi.budgeting.core.receipts.MostBoughtBy
import io.github.matteopi.budgeting.core.receipts.PurchasedItem
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** One row of "Most bought": a product, how many were bought and what they cost over the period. */
data class MostBoughtRow(val productId: Long, val name: String, val quantity: Int, val spend: Money)

/** One row of "Biggest price changes": unit price before and after. */
data class PriceChangeRow(
    val productId: Long,
    val name: String,
    val previous: Money,
    val latest: Money,
    /** Change relative to [previous]; null if the previous price was zero. */
    val percent: Double?,
) {
    val rose: Boolean get() = latest > previous
}

/** A product that has been bought at least once, for the search list. */
data class ProductOption(val productId: Long, val name: String)

/** Everything the Insights → Items card shows. */
data class ItemsCardState(
    /** False hides the whole card: nothing has been scanned yet. */
    val hasReceipts: Boolean = false,
    val mostBought: List<MostBoughtRow> = emptyList(),
    val priceChanges: List<PriceChangeRow> = emptyList(),
    /** Every product with a purchase, by name. */
    val products: List<ProductOption> = emptyList(),
)

/** Pure item-price logic behind the Insights → Items card and the item detail screen. */
object ItemInsights {
    private const val CARD_LIMIT = 5

    /**
     * The card for the period [from]..[to]: the top products by spend, the biggest price moves
     * (latest unit price against the one before it, among purchases in the period) and the list
     * of searchable products.
     */
    fun card(
        purchased: List<PurchasedItem>,
        products: List<Product>,
        hasReceipts: Boolean,
        from: LocalDate,
        to: LocalDate,
    ): ItemsCardState {
        val names = products.associate { it.id to it.name }
        val inPeriod = purchased.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val mostBought = ItemPrices.mostBought(inPeriod, limit = CARD_LIMIT, by = MostBoughtBy.SPEND).map { stats ->
            MostBoughtRow(stats.productId, names[stats.productId] ?: "Item", stats.quantity, stats.spend)
        }
        val priceChanges = ItemPrices.priceChanges(inPeriod)
            .sortedByDescending { (_, change) -> abs(change.percent ?: 0.0) }
            .take(CARD_LIMIT)
            .map { (productId, change) ->
                PriceChangeRow(
                    productId = productId,
                    name = names[productId] ?: "Item",
                    previous = change.previous.unitPrice,
                    latest = change.latest.unitPrice,
                    percent = change.percent,
                )
            }
        val options = purchased.map { it.productId }.distinct()
            .mapNotNull { id -> names[id]?.let { ProductOption(id, it) } }
            .sortedBy { it.name.lowercase(Locale.ENGLISH) }
        return ItemsCardState(hasReceipts, mostBought, priceChanges, options)
    }

    /** "↑ 10.0%" for a rise, "↓ 4.3%" for a fall; a bare arrow if there is no percentage. */
    fun changeText(percent: Double?, rose: Boolean): String {
        val arrow = if (rose) "↑" else "↓"
        return if (percent == null) arrow else String.format(Locale.ENGLISH, "%s %.1f%%", arrow, abs(percent))
    }

    /** Products whose name contains [query] (ignoring case), at most [limit]. A blank query matches none. */
    fun search(options: List<ProductOption>, query: String, limit: Int): List<ProductOption> {
        val needle = query.trim().lowercase(Locale.ENGLISH)
        if (needle.isEmpty()) return emptyList()
        return options.filter { it.name.lowercase(Locale.ENGLISH).contains(needle) }.take(limit)
    }

    /** The period's first day for the Insights period chips, counting back from [to]. */
    fun periodStart(period: InsightsPeriod, to: LocalDate): LocalDate = when (period) {
        InsightsPeriod.WEEKS_12 -> to.minusWeeks(12)
        InsightsPeriod.MONTHS_6 -> to.minusMonths(6)
        InsightsPeriod.MONTHS_12 -> to.minusMonths(12)
    }
}
