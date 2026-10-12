package io.github.matteopi.budgeting.ui.insights

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.receipts.PurchasedItem
import io.github.matteopi.budgeting.core.receipts.Store
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemInsightsTest {
    private val to = LocalDate.of(2026, 10, 12)
    private val from = to.minusWeeks(12)

    private val products = listOf(
        Product(id = 1, name = "Semi-skimmed milk 2 pints"),
        Product(id = 2, name = "Bananas"),
        Product(id = 3, name = "Bread"),
    )

    private fun bought(productId: Long, daysAgo: Long, price: Long, quantity: Int = 1, store: Store = Store.TESCO, discount: Long = 0) =
        PurchasedItem(
            productId = productId,
            date = to.minusDays(daysAgo),
            store = store,
            quantity = quantity,
            price = Money(price),
            discount = Money(discount),
        )

    @Test
    fun mostBoughtIsRankedBySpendWithQuantity() {
        val purchased = listOf(
            bought(1, 20, 150),
            bought(1, 5, 165, quantity = 1),
            bought(2, 4, 300, quantity = 6),
            bought(3, 3, 100),
        )
        val card = ItemInsights.card(purchased, products, hasReceipts = true, from = from, to = to)
        assertEquals(listOf(1L, 2L, 3L), card.mostBought.map { it.productId })
        assertEquals("Semi-skimmed milk 2 pints", card.mostBought[0].name)
        assertEquals(Money(315), card.mostBought[0].spend)
        assertEquals(2, card.mostBought[0].quantity)
        assertEquals("Bananas", card.mostBought[1].name)
        assertEquals(6, card.mostBought[1].quantity)
    }

    @Test
    fun mostBoughtShowsAtMostFiveAndOnlyPurchasesInThePeriod() {
        val manyProducts = (1L..7L).map { Product(id = it, name = "Item $it") }
        val purchased = (1L..7L).map { bought(it, 3, 100 * it) } + bought(1, 200, 99_999)
        val card = ItemInsights.card(purchased, manyProducts, hasReceipts = true, from = from, to = to)
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), card.mostBought.map { it.productId })
    }

    @Test
    fun priceChangesCompareLatestWithPreviousUnitPriceAndRankByBiggestMove() {
        val purchased = listOf(
            bought(1, 30, 150),
            bought(1, 2, 165),
            bought(2, 20, 100),
            bought(2, 3, 80, store = Store.SAINSBURYS),
            bought(3, 10, 100),
            bought(3, 1, 100),
        )
        val card = ItemInsights.card(purchased, products, hasReceipts = true, from = from, to = to)
        assertEquals(listOf(2L, 1L), card.priceChanges.map { it.productId })

        val bananas = card.priceChanges[0]
        assertEquals(Money(100), bananas.previous)
        assertEquals(Money(80), bananas.latest)
        assertEquals(-20.0, bananas.percent)
        assertTrue(!bananas.rose)

        val milk = card.priceChanges[1]
        assertTrue(milk.rose)
        assertEquals(10.0, milk.percent!!, 1e-9)
    }

    @Test
    fun priceChangesIgnoreTheDaysBeforeThePeriod() {
        val purchased = listOf(bought(1, 400, 100), bought(1, 2, 165))
        val card = ItemInsights.card(purchased, products, hasReceipts = true, from = from, to = to)
        assertTrue(card.priceChanges.isEmpty())
    }

    @Test
    fun productsListedByNameForSearch() {
        val purchased = listOf(bought(3, 5, 100), bought(1, 5, 100), bought(2, 400, 100))
        val card = ItemInsights.card(purchased, products, hasReceipts = true, from = from, to = to)
        assertEquals(listOf("Bananas", "Bread", "Semi-skimmed milk 2 pints"), card.products.map { it.name })
    }

    @Test
    fun hasReceiptsComesFromTheReceiptsNotTheItems() {
        assertTrue(!ItemInsights.card(emptyList(), emptyList(), hasReceipts = false, from = from, to = to).hasReceipts)
        val card = ItemInsights.card(emptyList(), emptyList(), hasReceipts = true, from = from, to = to)
        assertTrue(card.hasReceipts)
        assertTrue(card.mostBought.isEmpty())
    }

    @Test
    fun changeTextShowsArrowAndPercent() {
        assertEquals("↑ 10.0%", ItemInsights.changeText(10.0, rose = true))
        assertEquals("↓ 4.3%", ItemInsights.changeText(-4.3478, rose = false))
        assertEquals("↑", ItemInsights.changeText(null, rose = true))
    }

    @Test
    fun searchMatchesPartOfTheNameIgnoringCase() {
        val options = listOf(ProductOption(1, "Semi-skimmed milk"), ProductOption(2, "Oat milk"), ProductOption(3, "Bread"))
        assertEquals(listOf(1L, 2L), ItemInsights.search(options, "  MILK ", limit = 8).map { it.productId })
        assertEquals(listOf(1L), ItemInsights.search(options, "milk", limit = 1).map { it.productId })
        assertTrue(ItemInsights.search(options, "  ", limit = 8).isEmpty())
        assertTrue(ItemInsights.search(options, "cheese", limit = 8).isEmpty())
    }

    @Test
    fun periodStartCountsBackFromToday() {
        assertEquals(LocalDate.of(2026, 7, 20), ItemInsights.periodStart(InsightsPeriod.WEEKS_12, to))
        assertEquals(LocalDate.of(2026, 4, 12), ItemInsights.periodStart(InsightsPeriod.MONTHS_6, to))
        assertEquals(LocalDate.of(2025, 10, 12), ItemInsights.periodStart(InsightsPeriod.MONTHS_12, to))
    }
}
