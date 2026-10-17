package io.github.matteopi.budgeting.ui.receipts.list

import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.receipts.PurchasedItem
import io.github.matteopi.budgeting.core.receipts.Store
import java.util.Locale

/**
 * Pure helpers shared by the receipts list, receipt detail and the Insights item prices
 * (docs/PLAN.md §10.4). Kept free of Android and Compose so they can be unit-tested on the JVM.
 */
object ReceiptMapping {

    /** The [Store] a receipt's stored key names; anything unknown (or a later-added store) is [Store.OTHER]. */
    fun storeOf(key: String): Store = Store.entries.firstOrNull { it.name == key } ?: Store.OTHER

    /** How a store is written on screen. */
    fun storeName(store: Store): String = when (store) {
        Store.TESCO -> "Tesco"
        Store.SAINSBURYS -> "Sainsbury's"
        Store.MORRISONS -> "Morrisons"
        Store.OTHER -> "Shop"
    }

    /** [storeName] for a receipt's stored store key. */
    fun storeName(key: String): String = storeName(storeOf(key))

    /** The product's name when the item is linked to one, else the line as printed. */
    fun itemName(item: ReceiptItem, productsById: Map<Long, Product>): String =
        item.productId?.let { productsById[it]?.name } ?: item.rawName

    /**
     * How many (or how much) of an item was bought: "2 × £1.50" for a counted item with a unit
     * price, "2 ×" without one, "0.452 kg @ £1.20/kg" for a weighed item; null for a single
     * plain item.
     */
    fun quantityText(item: ReceiptItem): String? {
        val grams = item.weightGrams
        if (grams != null) {
            val kilograms = String.format(Locale.ENGLISH, "%.3f", grams / 1000.0).trimEnd('0').trimEnd('.')
            val perKilogram = item.unitPrice?.let { " @ ${it.format()}/kg" } ?: ""
            return "$kilograms kg$perKilogram"
        }
        if (item.quantity <= 1) return null
        val unit = item.unitPrice?.let { " ${it.format()}" } ?: ""
        return "${item.quantity} ×$unit"
    }

    /**
     * Joins saved items with their receipts (for the date and store) into the [PurchasedItem]s that
     * `ItemPrices` works on. Items that are not linked to a product, or whose receipt is missing,
     * are left out. Output keeps the order of [items]. A weighed item keeps its weight, which the
     * recipes section turns into a price per gram.
     */
    fun purchasedItems(receipts: List<Receipt>, items: List<ReceiptItem>): List<PurchasedItem> {
        val receiptsById = receipts.associateBy { it.id }
        return items.mapNotNull { item ->
            val productId = item.productId ?: return@mapNotNull null
            val receipt = receiptsById[item.receiptId] ?: return@mapNotNull null
            PurchasedItem(
                productId = productId,
                date = receipt.date,
                store = storeOf(receipt.store),
                quantity = item.quantity,
                unitPrice = item.unitPrice,
                price = item.price,
                discount = item.discount,
                weightGrams = item.weightGrams,
            )
        }
    }
}
