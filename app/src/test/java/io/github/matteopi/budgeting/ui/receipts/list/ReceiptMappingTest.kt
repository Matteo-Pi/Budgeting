package io.github.matteopi.budgeting.ui.receipts.list

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.receipts.Store
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptMappingTest {
    private val tesco = Receipt(id = 1, store = "TESCO", date = LocalDate.of(2026, 9, 20), total = Money(500), source = "PHOTO")
    private val sainsburys = Receipt(id = 2, store = "SAINSBURYS", date = LocalDate.of(2026, 10, 4), total = Money(300), source = "PHOTO")

    private fun item(
        receiptId: Long,
        productId: Long?,
        price: Long,
        quantity: Int = 1,
        unitPrice: Long? = null,
        discount: Long = 0,
        weightGrams: Int? = null,
        rawName: String = "RAW",
    ) = ReceiptItem(
        receiptId = receiptId,
        productId = productId,
        rawName = rawName,
        quantity = quantity,
        weightGrams = weightGrams,
        unitPrice = unitPrice?.let { Money(it) },
        price = Money(price),
        discount = Money(discount),
    )

    @Test
    fun storeNamesAreDisplayNames() {
        assertEquals("Tesco", ReceiptMapping.storeName("TESCO"))
        assertEquals("Sainsbury's", ReceiptMapping.storeName("SAINSBURYS"))
        assertEquals("Morrisons", ReceiptMapping.storeName("MORRISONS"))
        assertEquals("Shop", ReceiptMapping.storeName("OTHER"))
        assertEquals("Shop", ReceiptMapping.storeName("ALDI"))
        assertEquals(Store.OTHER, ReceiptMapping.storeOf(""))
    }

    @Test
    fun joinsItemsWithTheirReceiptsDateAndStore() {
        val items = listOf(
            item(1, productId = 10, price = 150),
            item(2, productId = 10, price = 300, quantity = 2, unitPrice = 155, discount = -10),
        )
        val purchased = ReceiptMapping.purchasedItems(listOf(tesco, sainsburys), items)
        assertEquals(2, purchased.size)

        val first = purchased[0]
        assertEquals(10L, first.productId)
        assertEquals(LocalDate.of(2026, 9, 20), first.date)
        assertEquals(Store.TESCO, first.store)
        assertEquals(Money(150), first.unit)

        val second = purchased[1]
        assertEquals(LocalDate.of(2026, 10, 4), second.date)
        assertEquals(Store.SAINSBURYS, second.store)
        assertEquals(2, second.quantity)
        assertEquals(Money(155), second.unit)
        assertEquals(Money(290), second.spend)
    }

    @Test
    fun itemsWithoutAProductOrWithoutAReceiptAreLeftOut() {
        val items = listOf(
            item(1, productId = null, price = 100),
            item(99, productId = 10, price = 100),
            item(1, productId = 11, price = 100),
        )
        val purchased = ReceiptMapping.purchasedItems(listOf(tesco), items)
        assertEquals(listOf(11L), purchased.map { it.productId })
    }

    @Test
    fun unitPriceIsDerivedFromTheLineWhenTheReceiptDidNotSayAndQuantityIsAboveOne() {
        val purchased = ReceiptMapping.purchasedItems(listOf(tesco), listOf(item(1, productId = 10, price = 301, quantity = 2)))
        assertEquals(Money(151), purchased.single().unit)
    }

    @Test
    fun emptyInputsGiveNoItems() {
        assertTrue(ReceiptMapping.purchasedItems(emptyList(), emptyList()).isEmpty())
        assertTrue(ReceiptMapping.purchasedItems(listOf(tesco), emptyList()).isEmpty())
    }

    @Test
    fun itemNameUsesTheProductWhenLinkedElseTheRawLine() {
        val products = mapOf(10L to Product(id = 10, name = "Semi-skimmed milk 2 pints"))
        assertEquals("Semi-skimmed milk 2 pints", ReceiptMapping.itemName(item(1, 10, 150, rawName = "TS MLK"), products))
        assertEquals("TS MLK", ReceiptMapping.itemName(item(1, null, 150, rawName = "TS MLK"), products))
        assertEquals("TS MLK", ReceiptMapping.itemName(item(1, 77, 150, rawName = "TS MLK"), products))
    }

    @Test
    fun quantityTextDescribesCountsAndWeights() {
        assertNull(ReceiptMapping.quantityText(item(1, 10, 150)))
        assertEquals("2 × £1.50", ReceiptMapping.quantityText(item(1, 10, 300, quantity = 2, unitPrice = 150)))
        assertEquals("3 ×", ReceiptMapping.quantityText(item(1, 10, 300, quantity = 3)))
        assertEquals("0.452 kg @ £1.20/kg", ReceiptMapping.quantityText(item(1, 10, 54, weightGrams = 452, unitPrice = 120)))
        assertEquals("1.5 kg", ReceiptMapping.quantityText(item(1, 10, 180, weightGrams = 1_500)))
    }
}
