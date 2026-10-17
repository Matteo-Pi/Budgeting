package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.receipts.PurchasedItem
import io.github.matteopi.budgeting.core.receipts.Store
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricesTest {

    private val today = LocalDate.of(2026, 10, 17)

    private fun mince(
        productId: Long? = 7,
        price: Long? = 505,
        priceDate: LocalDate? = today.minusDays(2),
        unit: BaseUnit = BaseUnit.G,
        pack: Double = 500.0,
    ) = Ingredient(
        id = 1, name = "Mince", unit = unit, packSize = pack, price = price?.let { Money(it) },
        priceDate = priceDate, store = "Tesco", productId = productId,
    )

    private fun buy(
        date: LocalDate,
        price: Long,
        quantity: Int = 1,
        discount: Long = 0,
        weight: Int? = null,
        store: Store = Store.TESCO,
        product: Long = 7,
    ) = PurchasedItem(product, date, store, quantity, null, Money(price), Money(discount), weight)

    private fun day(daysAgo: Long) = today.minusDays(daysAgo)

    @Test
    fun `a pack bought once gives its price over the pack size`() {
        val p = assertNotNull(Prices.resolve(mince(), listOf(buy(day(3), 600)), today))
        assertEquals(1.2, p.perUnitPence, 1e-9)
        assertEquals(Money(600), p.packPrice)
        assertEquals(PriceSource.Receipt(day(3), Store.TESCO), p.source)
        assertFalse(p.old)
    }

    @Test
    fun `a weighed item gives the price per gram directly`() {
        val p = assertNotNull(Prices.resolve(mince(), listOf(buy(day(1), 240, weight = 480)), today))
        assertEquals(0.5, p.perUnitPence, 1e-9)
        assertEquals(Money(250), p.packPrice, "0.5 p a gram over a 500 g pack")
        assertIs<PriceSource.Receipt>(p.source)
    }

    @Test
    fun `a weighed item counts a gram as a millilitre for a millilitre ingredient`() {
        val oil = mince(unit = BaseUnit.ML, pack = 1000.0)
        val p = assertNotNull(Prices.resolve(oil, listOf(buy(day(1), 350, weight = 500)), today))
        assertEquals(0.7, p.perUnitPence, 1e-9)
        assertEquals(Money(700), p.packPrice)
    }

    @Test
    fun `a line of several packs is the spend over the quantity`() {
        val p = assertNotNull(Prices.resolve(mince(), listOf(buy(day(1), 1000, quantity = 2)), today))
        assertEquals(Money(500), p.packPrice)
        assertEquals(1.0, p.perUnitPence, 1e-9)
        val odd = assertNotNull(Prices.resolve(mince(), listOf(buy(day(1), 1000, quantity = 3)), today))
        assertEquals(1000 / 3.0 / 500, odd.perUnitPence, 1e-9, "the per unit price is not rounded")
        assertEquals(Money(333), odd.packPrice)
    }

    @Test
    fun `the price paid is after a Clubcard or multibuy discount`() {
        val p = assertNotNull(Prices.resolve(mince(), listOf(buy(day(1), 600, discount = -100)), today))
        assertEquals(Money(500), p.packPrice)
        assertEquals(1.0, p.perUnitPence, 1e-9)
        val multi = assertNotNull(Prices.resolve(mince(), listOf(buy(day(1), 1200, quantity = 2, discount = -200)), today))
        assertEquals(Money(500), multi.packPrice)
    }

    @Test
    fun `a free item is skipped for the one before it`() {
        val free = buy(day(1), 300, discount = -300)
        val p = assertNotNull(Prices.resolve(mince(), listOf(buy(day(10), 580), free), today))
        assertEquals(Money(580), p.packPrice)
        assertEquals(day(10), p.source.dateOrNull)
        val negative = buy(day(1), 100, discount = -150)
        assertEquals(Money(580), Prices.resolve(mince(), listOf(buy(day(10), 580), negative), today)!!.packPrice)
    }

    @Test
    fun `only free purchases fall back to the typed price`() {
        val p = assertNotNull(Prices.resolve(mince(), listOf(buy(day(1), 300, discount = -300)), today))
        assertEquals(PriceSource.Typed(day(2)), p.source)
        assertEquals(Money(505), p.packPrice)
    }

    @Test
    fun `the latest purchase wins, by date and then by input order`() {
        val older = buy(day(20), 500)
        val newer = buy(day(5), 560)
        assertEquals(Money(560), Prices.resolve(mince(), listOf(older, newer), today)!!.packPrice)
        assertEquals(Money(560), Prices.resolve(mince(), listOf(newer, older), today)!!.packPrice, "input order doesn't beat the date")
        val sameDayA = buy(day(5), 560, store = Store.TESCO)
        val sameDayB = buy(day(5), 590, store = Store.SAINSBURYS)
        val p = assertNotNull(Prices.resolve(mince(), listOf(sameDayA, sameDayB), today))
        assertEquals(Money(590), p.packPrice, "on one date the later in the list wins")
        assertEquals(PriceSource.Receipt(day(5), Store.SAINSBURYS), p.source)
    }

    @Test
    fun `a receipt price beats the typed price`() {
        val p = assertNotNull(Prices.resolve(mince(price = 505), listOf(buy(day(1), 450)), today))
        assertEquals(Money(450), p.packPrice)
        assertIs<PriceSource.Receipt>(p.source)
    }

    @Test
    fun `an ingredient with no product link uses its typed price`() {
        val p = assertNotNull(Prices.resolve(mince(productId = null), listOf(buy(day(1), 450)), today))
        assertEquals(PriceSource.Typed(day(2)), p.source)
        assertEquals(Money(505), p.packPrice)
        assertEquals(1.01, p.perUnitPence, 1e-9)
    }

    @Test
    fun `purchases of other products are ignored`() {
        val p = assertNotNull(Prices.resolve(mince(), listOf(buy(day(1), 450, product = 8)), today))
        assertIs<PriceSource.Typed>(p.source)
    }

    @Test
    fun `no purchase and no typed price is no price`() {
        assertNull(Prices.resolve(mince(price = null), emptyList(), today))
        assertNull(Prices.resolve(mince(productId = null, price = null), listOf(buy(day(1), 450)), today))
        assertNull(Prices.resolve(mince(price = null), listOf(buy(day(1), 300, discount = -300)), today))
    }

    @Test
    fun `a price more than 120 days old is marked old`() {
        assertFalse(Prices.resolve(mince(), listOf(buy(day(120), 500)), today)!!.old)
        assertTrue(Prices.resolve(mince(), listOf(buy(day(121), 500)), today)!!.old)
        assertFalse(Prices.resolve(mince(priceDate = day(120)), emptyList(), today)!!.old)
        assertTrue(Prices.resolve(mince(priceDate = day(121)), emptyList(), today)!!.old)
        assertEquals(120L, Prices.OLD_AFTER_DAYS)
    }

    @Test
    fun `a typed price with no date is not old and says so`() {
        val p = assertNotNull(Prices.resolve(mince(priceDate = null), emptyList(), today))
        assertEquals(PriceSource.Typed(null), p.source)
        assertFalse(p.old)
        assertNull(p.source.dateOrNull)
    }

    @Test
    fun `an unweighed purchase of zero weight is treated as unweighed`() {
        val p = assertNotNull(Prices.resolve(mince(), listOf(buy(day(1), 600, weight = 0)), today))
        assertEquals(Money(600), p.packPrice)
    }

    @Test
    fun `a pack size of zero cannot be priced`() {
        assertNull(Prices.resolve(mince(pack = 0.0), listOf(buy(day(1), 600)), today))
    }

    @Test
    fun `resolve all keeps each ingredient's own purchases`() {
        val a = mince(productId = 7).copy(id = 1)
        val b = mince(productId = 8, price = 200).copy(id = 2, name = "Other")
        val c = mince(productId = null, price = null).copy(id = 3, name = "Nothing")
        val prices = Prices.resolveAll(listOf(a, b, c), listOf(buy(day(1), 600), buy(day(1), 350, product = 8)), today)
        assertEquals(setOf(1L, 2L), prices.keys)
        assertEquals(Money(600), prices.getValue(1).packPrice)
        assertEquals(Money(350), prices.getValue(2).packPrice)
    }

    @Test
    fun `purchased items keep working without a weight`() {
        val item = PurchasedItem(1, today, Store.TESCO, 1, null, Money(100))
        assertNull(item.weightGrams)
        assertEquals(Money(100), item.spend)
        assertEquals(480, buy(today, 100, weight = 480).weightGrams)
    }
}
