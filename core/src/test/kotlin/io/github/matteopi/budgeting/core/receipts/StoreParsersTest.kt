package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreParsersTest {

    private fun parse(text: String, source: ReceiptSource = ReceiptSource.SHARED_TEXT) =
        ReceiptParsers.parseText(text, source)

    private fun ReceiptDraft.item(name: String, index: Int = 0) = items.filter { it.rawName == name }[index]

    // ---- Tesco ----

    @Test
    fun `tesco receipt is detected, dated and totalled`() {
        val draft = parse(ReceiptFixtures.TESCO)
        assertEquals(Store.TESCO, draft.store)
        assertEquals(LocalDate.of(2026, 10, 12), draft.date)
        assertEquals(ReceiptFixtures.TESCO_TOTAL, draft.total)
        assertEquals(ReceiptSource.SHARED_TEXT, draft.source)
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
        assertTrue(draft.warnings.isEmpty(), draft.warnings.toString())
    }

    @Test
    fun `tesco item list has exactly the purchased items in order`() {
        val draft = parse(ReceiptFixtures.TESCO)
        assertEquals(
            listOf(
                "MILK SEMI SKIMMED 2PT",
                "HOVIS MEDIUM SLICED 800G",
                "TS FINEST SALMON FILLETS",
                "BANANAS LOOSE",
                "CHEDDAR MATURE 350G",
                "YOGHURT STRAWBERRY",
                "YOGHURT STRAWBERRY",
                "YOGHURT STRAWBERRY",
                "SATSUMAS EASY PEEL",
                "5P CARRIER BAG",
            ),
            draft.items.map { it.rawName },
        )
    }

    @Test
    fun `tesco plain item has quantity one, no unit price and full confidence`() {
        val milk = parse(ReceiptFixtures.TESCO).item("MILK SEMI SKIMMED 2PT")
        assertEquals(Money(145), milk.price)
        assertEquals(1, milk.quantity)
        assertNull(milk.unitPrice)
        assertNull(milk.weightGrams)
        assertEquals(Money.ZERO, milk.discount)
        assertEquals(1f, milk.confidence)
    }

    @Test
    fun `trailing till code letter is dropped and clubcard price attaches to the item above`() {
        val hovis = parse(ReceiptFixtures.TESCO).item("HOVIS MEDIUM SLICED 800G")
        assertEquals(Money(130), hovis.price)
        assertEquals(Money(-30), hovis.discount)
        assertEquals(Money(100), hovis.spend)
    }

    @Test
    fun `CC PRICE with a pound sign attaches to the item above`() {
        val salmon = parse(ReceiptFixtures.TESCO).item("TS FINEST SALMON FILLETS")
        assertEquals(Money(450), salmon.price)
        assertEquals(Money(-100), salmon.discount)
    }

    @Test
    fun `weighed item takes name from the line above and weight and price per kilo from the weight line`() {
        val bananas = parse(ReceiptFixtures.TESCO).item("BANANAS LOOSE")
        assertEquals(452, bananas.weightGrams)
        assertEquals(Money(120), bananas.unitPrice)
        assertEquals(Money(54), bananas.price)
        assertEquals(1, bananas.quantity)
        assertEquals(1f, bananas.confidence)
    }

    @Test
    fun `quantity line after the item sets quantity and unit price`() {
        val cheddar = parse(ReceiptFixtures.TESCO).item("CHEDDAR MATURE 350G")
        assertEquals(2, cheddar.quantity)
        assertEquals(Money(110), cheddar.unitPrice)
        assertEquals(Money(220), cheddar.price)
    }

    @Test
    fun `quantity line before the item applies to the next item`() {
        val satsumas = parse(ReceiptFixtures.TESCO).item("SATSUMAS EASY PEEL")
        assertEquals(3, satsumas.quantity)
        assertEquals(Money(85), satsumas.unitPrice)
        assertEquals(Money(255), satsumas.price)
    }

    @Test
    fun `multibuy line stays on the last item above even though it names no item`() {
        val yoghurts = parse(ReceiptFixtures.TESCO).items.filter { it.rawName == "YOGHURT STRAWBERRY" }
        assertEquals(3, yoghurts.size)
        assertEquals(listOf(Money.ZERO, Money.ZERO, Money(-30)), yoghurts.map { it.discount })
        assertEquals(listOf(Money(60), Money(60), Money(60)), yoghurts.map { it.price })
    }

    @Test
    fun `bag charge is an ordinary item`() {
        val bag = parse(ReceiptFixtures.TESCO).item("5P CARRIER BAG")
        assertEquals(Money(10), bag.price)
    }

    @Test
    fun `payment change vat points and footer lines are not items`() {
        val names = parse(ReceiptFixtures.TESCO).items.map { it.rawName.uppercase() }
        for (noise in listOf("VISA", "CHANGE", "VAT", "CLUBCARD", "THANK", "SUBTOTAL", "TOTAL")) {
            assertTrue(names.none { noise in it }, "$noise leaked into $names")
        }
    }

    @Test
    fun `tesco is also found from clubcard price wording when the header is unreadable`() {
        val text = """
            xx
            MILK 1.00
            Clubcard Price -0.20
            TOTAL 0.80
        """.trimIndent()
        val draft = parse(text)
        assertEquals(Store.TESCO, draft.store)
        assertEquals(Money(-20), draft.items.single().discount)
    }

    // ---- Sainsbury's ----

    @Test
    fun `sainsburys receipt is detected, dated and totalled`() {
        val draft = parse(ReceiptFixtures.SAINSBURYS)
        assertEquals(Store.SAINSBURYS, draft.store)
        assertEquals(LocalDate.of(2026, 10, 12), draft.date)
        assertEquals(ReceiptFixtures.SAINSBURYS_TOTAL, draft.total)
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
        assertTrue(draft.warnings.isEmpty(), draft.warnings.toString())
    }

    @Test
    fun `sainsburys items`() {
        val draft = parse(ReceiptFixtures.SAINSBURYS)
        assertEquals(
            listOf(
                "SO ORGANIC SEMI SKIMMED MILK 2L",
                "BASICS WHITE BREAD",
                "FRESH CHICKEN BREAST 650G",
                "LOOSE ONIONS",
                "PIZZA MARGHERITA",
                "ICE CREAM VANILLA",
                "ICE CREAM VANILLA",
                "BAG FOR LIFE",
            ),
            draft.items.map { it.rawName },
        )
    }

    @Test
    fun `nectar price attaches to the chicken`() {
        val chicken = parse(ReceiptFixtures.SAINSBURYS).item("FRESH CHICKEN BREAST 650G")
        assertEquals(Money(420), chicken.price)
        assertEquals(Money(-70), chicken.discount)
    }

    @Test
    fun `weighed onions and 2 x quantity line after pizza`() {
        val draft = parse(ReceiptFixtures.SAINSBURYS)
        val onions = draft.item("LOOSE ONIONS")
        assertEquals(320, onions.weightGrams)
        assertEquals(Money(110), onions.unitPrice)
        assertEquals(Money(35), onions.price)
        val pizza = draft.item("PIZZA MARGHERITA")
        assertEquals(2, pizza.quantity)
        assertEquals(Money(125), pizza.unitPrice)
    }

    @Test
    fun `multibuy discount lands on the second ice cream`() {
        val ice = parse(ReceiptFixtures.SAINSBURYS).items.filter { it.rawName == "ICE CREAM VANILLA" }
        assertEquals(listOf(Money.ZERO, Money(-100)), ice.map { it.discount })
    }

    // ---- Morrisons ----

    @Test
    fun `morrisons receipt is detected, dated and totalled`() {
        val draft = parse(ReceiptFixtures.MORRISONS)
        assertEquals(Store.MORRISONS, draft.store)
        assertEquals(LocalDate.of(2026, 10, 12), draft.date)
        assertEquals(ReceiptFixtures.MORRISONS_TOTAL, draft.total)
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
        assertTrue(draft.warnings.isEmpty(), draft.warnings.toString())
    }

    @Test
    fun `morrisons more card lines`() {
        val draft = parse(ReceiptFixtures.MORRISONS)
        assertEquals(
            listOf(
                "M SEMI SKIMMED MILK 4PT",
                "M CHICKEN THIGHS 1KG",
                "BANANAS LOOSE",
                "M SAVERS BAKED BEANS",
                "M CARRIER BAG",
                "M FRUIT AND NUT",
            ),
            draft.items.map { it.rawName },
        )
        assertEquals(Money(-50), draft.item("M CHICKEN THIGHS 1KG").discount)
        assertEquals(Money(-20), draft.item("M FRUIT AND NUT").discount)
    }

    @Test
    fun `morrisons inline weight and quantity before item`() {
        val draft = parse(ReceiptFixtures.MORRISONS)
        val bananas = draft.item("BANANAS LOOSE")
        assertEquals(612, bananas.weightGrams)
        assertEquals(Money(95), bananas.unitPrice)
        assertEquals(Money(58), bananas.price)
        val beans = draft.item("M SAVERS BAKED BEANS")
        assertEquals(4, beans.quantity)
        assertEquals(Money(45), beans.unitPrice)
        assertEquals(Money(180), beans.price)
    }

    @Test
    fun `more card number line is not an item or a discount`() {
        val draft = parse(ReceiptFixtures.MORRISONS)
        assertEquals(6, draft.items.size)
    }

    // ---- Generic ----

    @Test
    fun `unknown store uses the generic parser and still reads discounts`() {
        val draft = parse(ReceiptFixtures.GENERIC)
        assertEquals(Store.OTHER, draft.store)
        assertEquals(LocalDate.of(2026, 10, 12), draft.date)
        assertEquals(Money(324), draft.total)
        assertEquals(listOf("SANDWICH CHICKEN SALAD", "CRISPS SALT & VINEGAR"), draft.items.map { it.rawName })
        assertEquals(Money(-50), draft.items[0].discount)
        assertEquals(Money(-25), draft.items[1].discount)
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
    }

    @Test
    fun `generic parser reads any store's loyalty wording`() {
        val text = """
            SOME SHOP
            COFFEE 3.00
            Nectar Price -0.50
            TEA 2.00
            Clubcard Price -0.25
            BISCUITS 1.00
            More Card Price -0.10
            TOTAL 5.15
        """.trimIndent()
        val draft = GenericParser.parse(LineGrouping.rows(text), ReceiptSource.SHARED_TEXT)
        assertEquals(listOf(Money(-50), Money(-25), Money(-10)), draft.items.map { it.discount })
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
    }

    // ---- Selection ----

    @Test
    fun `parsers detect only their own store`() {
        val tesco = LineGrouping.rows(ReceiptFixtures.TESCO)
        val sains = LineGrouping.rows(ReceiptFixtures.SAINSBURYS)
        val morr = LineGrouping.rows(ReceiptFixtures.MORRISONS)
        assertTrue(TescoParser.detect(tesco))
        assertFalse(SainsburysParser.detect(tesco))
        assertFalse(MorrisonsParser.detect(tesco))
        assertTrue(SainsburysParser.detect(sains))
        assertFalse(TescoParser.detect(sains))
        assertTrue(MorrisonsParser.detect(morr))
        assertFalse(SainsburysParser.detect(morr))
        assertTrue(GenericParser.detect(emptyList()))
    }

    @Test
    fun `the store name is only looked for near the top`() {
        val rows = LineGrouping.rows(
            (List(20) { "FILLER LINE $it" } + "TESCO").joinToString("\n"),
        )
        assertFalse(TescoParser.detect(rows))
    }

    @Test
    fun `store name misread with a zero is still found`() {
        assertTrue(TescoParser.detect(LineGrouping.rows("TESC0 EXTRA\nMILK 1.00")))
        assertTrue(MorrisonsParser.detect(LineGrouping.rows("M0RRISONS\nMILK 1.00")))
    }

    @Test
    fun `empty input gives an empty draft with warnings`() {
        val draft = ReceiptParsers.parse(emptyList(), ReceiptSource.PHOTO)
        assertEquals(Store.OTHER, draft.store)
        assertTrue(draft.items.isEmpty())
        assertNull(draft.total)
        assertNull(draft.date)
        assertEquals(ReceiptCheck.NoTotal, ReceiptChecks.check(draft))
        assertTrue(draft.warnings.isNotEmpty())
    }

    @Test
    fun `source is passed through`() {
        assertEquals(ReceiptSource.SHARED_PDF, parse(ReceiptFixtures.TESCO, ReceiptSource.SHARED_PDF).source)
        assertNotNull(parse(ReceiptFixtures.TESCO, ReceiptSource.DATA_EXPORT))
    }
}
