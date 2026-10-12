package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemPricesTest {

    private fun d(month: Int, day: Int) = LocalDate.of(2026, month, day)

    private fun buy(
        product: Long,
        date: LocalDate,
        price: Long,
        store: Store = Store.TESCO,
        quantity: Int = 1,
        unitPrice: Long? = null,
        discount: Long = 0,
    ) = PurchasedItem(product, date, store, quantity, unitPrice?.let { Money(it) }, Money(price), Money(discount))

    private val milk = 1L
    private val bread = 2L
    private val eggs = 3L

    private val items = listOf(
        buy(milk, d(9, 1), 145),
        buy(bread, d(9, 3), 130, discount = -30),
        buy(milk, d(9, 20), 150, Store.SAINSBURYS),
        buy(eggs, d(9, 25), 600, quantity = 2, unitPrice = 300),
        buy(milk, d(10, 5), 160),
        buy(eggs, d(10, 6), 300),
        buy(milk, d(10, 12), 160, quantity = 2, unitPrice = 80),
    )

    @Test
    fun `unit price is the given one, else price divided by quantity rounded`() {
        assertEquals(Money(300), buy(1, d(9, 1), 600, quantity = 2, unitPrice = 300).unit)
        assertEquals(Money(145), buy(1, d(9, 1), 145).unit)
        assertEquals(Money(100), buy(1, d(9, 1), 200, quantity = 2).unit)
        assertEquals(Money(34), buy(1, d(9, 1), 101, quantity = 3).unit)
        assertEquals(Money(33), buy(1, d(9, 1), 100, quantity = 3).unit)
        assertEquals(Money(33), buy(1, d(9, 1), 99, quantity = 3).unit)
    }

    @Test
    fun `history lists unit prices oldest first with the store`() {
        val history = ItemPrices.history(items.reversed(), milk)
        assertEquals(
            listOf(
                PricePoint(d(9, 1), Store.TESCO, Money(145)),
                PricePoint(d(9, 20), Store.SAINSBURYS, Money(150)),
                PricePoint(d(10, 5), Store.TESCO, Money(160)),
                PricePoint(d(10, 12), Store.TESCO, Money(80)),
            ),
            history,
        )
    }

    @Test
    fun `history can be limited to one store`() {
        val history = ItemPrices.history(items, milk, Store.TESCO)
        assertEquals(listOf(Money(145), Money(160), Money(80)), history.map { it.unitPrice })
    }

    @Test
    fun `unknown product has no history`() {
        assertTrue(ItemPrices.history(items, 99).isEmpty())
    }

    @Test
    fun `change compares the latest unit price with the previous one`() {
        val change = ItemPrices.change(ItemPrices.history(items, milk, Store.TESCO))
        assertNotNull(change)
        assertEquals(Money(160), change.previous.unitPrice)
        assertEquals(Money(80), change.latest.unitPrice)
        assertEquals(Money(-80), change.difference)
        assertEquals(-50.0, change.percent!!, 1e-9)
    }

    @Test
    fun `a price rise is a positive difference and percentage`() {
        val change = ItemPrices.change(ItemPrices.history(items, milk).take(3))!!
        assertEquals(Money(10), change.difference)
        assertEquals(10.0 / 150 * 100, change.percent!!, 1e-9)
    }

    @Test
    fun `one purchase has no change`() {
        assertNull(ItemPrices.change(ItemPrices.history(items, bread)))
    }

    @Test
    fun `histories cover every product ordered by id`() {
        val histories = ItemPrices.histories(items)
        assertEquals(listOf(milk, bread, eggs), histories.map { it.productId })
        assertEquals(4, histories[0].points.size)
        assertEquals(Money(80), histories[0].latest?.unitPrice)
        assertNull(histories[1].change)
        assertEquals(Money.ZERO, histories[2].change?.difference)
    }

    @Test
    fun `price changes list only products whose price moved`() {
        assertEquals(listOf(milk), ItemPrices.priceChanges(items).map { it.first })
    }

    @Test
    fun `price changes are ordered biggest rise first`() {
        val moving = listOf(
            buy(1, d(9, 1), 100), buy(1, d(9, 8), 110),
            buy(2, d(9, 1), 200), buy(2, d(9, 8), 300),
            buy(3, d(9, 1), 500), buy(3, d(9, 8), 400),
        )
        assertEquals(listOf(2L, 1L, 3L), ItemPrices.priceChanges(moving).map { it.first })
    }

    @Test
    fun `price changes skip products with an unchanged price`() {
        val same = listOf(buy(1, d(9, 1), 100), buy(1, d(9, 8), 100))
        assertTrue(ItemPrices.priceChanges(same).isEmpty())
    }

    @Test
    fun `spend counts price plus discount over an inclusive period`() {
        assertEquals(Money(1615), ItemPrices.spend(items))
        assertEquals(Money(145 + 100), ItemPrices.spend(items, from = d(9, 1), to = d(9, 3)))
        assertEquals(Money(160 + 300 + 160), ItemPrices.spend(items, from = d(10, 1)))
        assertEquals(Money(145 + 150 + 160 + 160), ItemPrices.spend(items, productId = milk))
        assertEquals(Money(150), ItemPrices.spend(items, milk, from = d(9, 20), to = d(9, 20)))
        assertEquals(Money.ZERO, ItemPrices.spend(items, to = d(8, 31)))
    }

    @Test
    fun `spend by product is highest first`() {
        val byProduct = ItemPrices.spendByProduct(items)
        assertEquals(listOf(eggs, milk, bread), byProduct.map { it.first })
        assertEquals(Money(900), byProduct[0].second)
        assertEquals(Money(615), byProduct[1].second)
        assertEquals(Money(100), byProduct[2].second)
    }

    @Test
    fun `most bought ranks by quantity by default and respects the period and limit`() {
        val top = ItemPrices.mostBought(items)
        assertEquals(listOf(milk, eggs, bread), top.map { it.productId })
        assertEquals(5, top[0].quantity)
        assertEquals(4, top[0].purchases)
        assertEquals(d(10, 12), top[0].lastBought)
        assertEquals(Money(145 + 150 + 160 + 160), top[0].spend)
        assertEquals(listOf(milk), ItemPrices.mostBought(items, limit = 1).map { it.productId })
        assertEquals(listOf(eggs), ItemPrices.mostBought(items, from = d(9, 25), to = d(9, 30)).map { it.productId })
    }

    @Test
    fun `most bought can rank by purchases or by spend`() {
        val manyBags = listOf(buy(1, d(9, 1), 150, quantity = 10), buy(2, d(9, 2), 50), buy(2, d(9, 3), 50))
        assertEquals(listOf(1L, 2L), ItemPrices.mostBought(manyBags, by = MostBoughtBy.QUANTITY).map { it.productId })
        assertEquals(listOf(2L, 1L), ItemPrices.mostBought(manyBags, by = MostBoughtBy.PURCHASES).map { it.productId })
        assertEquals(listOf(1L, 2L), ItemPrices.mostBought(manyBags, by = MostBoughtBy.SPEND).map { it.productId })
    }

    @Test
    fun `ties break deterministically by the other measures then product id`() {
        val tied = listOf(buy(5, d(9, 1), 100), buy(4, d(9, 1), 100))
        assertEquals(listOf(4L, 5L), ItemPrices.mostBought(tied).map { it.productId })
    }

    @Test
    fun `empty input is fine everywhere`() {
        assertTrue(ItemPrices.histories(emptyList()).isEmpty())
        assertTrue(ItemPrices.mostBought(emptyList()).isEmpty())
        assertTrue(ItemPrices.spendByProduct(emptyList()).isEmpty())
        assertEquals(Money.ZERO, ItemPrices.spend(emptyList()))
    }
}
