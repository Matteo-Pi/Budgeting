package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserEdgeCasesTest {

    private fun parse(text: String) = ReceiptParsers.parseText(text.trimIndent(), ReceiptSource.SHARED_TEXT)

    // ---- discounts ----

    @Test
    fun `negative amount forms are all discounts on the item above`() {
        val draft = parse(
            """
            A ITEM 5.00
            -0.50
            B ITEM 5.00
            0.40-
            C ITEM 5.00
            £0.30 off
            D ITEM 5.00
            SOMETHING SPECIAL -£0.20
            TOTAL 18.60
            """,
        )
        assertEquals(listOf(-50L, -40L, -30L, -20L), draft.items.map { it.discount.pence })
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
    }

    @Test
    fun `keyword lines with a positive amount are still discounts`() {
        val draft = parse(
            """
            A ITEM 5.00
            PROMOTION 0.50
            B ITEM 5.00
            COUPON 1.00
            C ITEM 5.00
            MULTIBUY SAVING 0.25
            D ITEM 5.00
            REDUCED TO CLEAR 0.75
            TOTAL 16.50
            """,
        )
        assertEquals(listOf(-50L, -100L, -25L, -75L), draft.items.map { it.discount.pence })
    }

    @Test
    fun `reduced as part of a product name is not a discount`() {
        val draft = parse(
            """
            REDUCED FAT MILK 1.20
            OFFER SPREAD 2.00
            TOTAL 3.20
            """,
        )
        assertEquals(listOf("REDUCED FAT MILK", "OFFER SPREAD"), draft.items.map { it.rawName })
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
    }

    @Test
    fun `bare reduced or offer with an amount is a discount`() {
        val draft = parse(
            """
            A ITEM 5.00
            REDUCED 0.40
            B ITEM 5.00
            SPECIAL OFFER -0.60
            TOTAL 9.00
            """,
        )
        assertEquals(listOf(-40L, -60L), draft.items.map { it.discount.pence })
    }

    @Test
    fun `an offer description without a saving amount is ignored`() {
        val draft = parse(
            """
            YOGHURT 1.00
            3 FOR £2.00
            YOGHURT 1.00
            YOGHURT 1.00
            3 for £2.00 -1.00
            TOTAL 2.00
            """,
        )
        assertEquals(3, draft.items.size)
        assertEquals(listOf(0L, 0L, -100L), draft.items.map { it.discount.pence })
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
    }

    @Test
    fun `a multibuy that names no item goes on the item above it`() {
        val draft = parse(
            """
            CRISPS 1.50
            CHOCOLATE 1.00
            MULTIBUY -0.50
            TOTAL 2.00
            """,
        )
        assertEquals(Money.ZERO, draft.items[0].discount)
        assertEquals(Money(-50), draft.items[1].discount)
    }

    @Test
    fun `several discounts on one item add up`() {
        val draft = parse(
            """
            STEAK 6.00
            Clubcard Price -1.00
            COUPON -0.50
            TOTAL 4.50
            """,
        )
        assertEquals(Money(-150), draft.items.single().discount)
    }

    @Test
    fun `a discount with no item above is reported and ignored`() {
        val draft = parse(
            """
            Clubcard Price -0.50
            MILK 1.00
            TOTAL 1.00
            """,
        )
        assertEquals(Money.ZERO, draft.items.single().discount)
        assertTrue(draft.warnings.any { "no item above" in it }, draft.warnings.toString())
    }

    @Test
    fun `a discount larger than the price lowers confidence`() {
        val items = parse("MILK 1.00\nCOUPON -2.00\nTOTAL -1.00").items
        assertTrue(items.single().confidence < 1f)
    }

    // ---- quantity and weight ----

    @Test
    fun `x style quantity line after the item`() {
        val item = parse("BREAD ROLLS 1.80\n6 x 0.30\nTOTAL 1.80").items.single()
        assertEquals(6, item.quantity)
        assertEquals(Money(30), item.unitPrice)
        assertEquals(Money(180), item.price)
    }

    @Test
    fun `quantity line that carries its own total after a name only line`() {
        val item = parse("BREAD ROLLS\n6 @ £0.30  1.80\nTOTAL 1.80").items.single()
        assertEquals("BREAD ROLLS", item.rawName)
        assertEquals(6, item.quantity)
        assertEquals(Money(180), item.price)
    }

    @Test
    fun `leading quantity in the item name`() {
        val item = parse("2 X SEMI SKIMMED MILK 2.20\nTOTAL 2.20").items.single()
        assertEquals("SEMI SKIMMED MILK", item.rawName)
        assertEquals(2, item.quantity)
        assertEquals(Money(110), item.unitPrice)
        assertEquals(Money(220), item.price)
    }

    @Test
    fun `quantity that does not match the price lowers confidence`() {
        val items = parse("CHEESE 3.00\n2 @ £1.10\nTOTAL 3.00").items
        assertEquals(2, items.single().quantity)
        assertTrue(items.single().confidence < 1f)
    }

    @Test
    fun `weight line on its own row then the price on the next row`() {
        val item = parse("BANANAS LOOSE\n0.452 kg @ £1.20/kg\n0.54\nTOTAL 0.54").items.single()
        assertEquals("BANANAS LOOSE", item.rawName)
        assertEquals(452, item.weightGrams)
        assertEquals(Money(120), item.unitPrice)
        assertEquals(Money(54), item.price)
    }

    @Test
    fun `weight line after the item line`() {
        val item = parse("BANANAS LOOSE 0.54\n0.452 kg @ £1.20/kg\nTOTAL 0.54").items.single()
        assertEquals(452, item.weightGrams)
        assertEquals(Money(120), item.unitPrice)
        assertEquals(Money(54), item.price)
    }

    @Test
    fun `weight variants`() {
        val items = parse(
            """
            GRAPES
            0.5kg @ £4.00 /kg   2.00
            CHEESE
            250 g @ £8.00 per kg   2.00
            APPLES 1.100 KG @ 1.50/KG 1.65
            TOTAL 5.65
            """,
        ).items
        assertEquals(listOf(500, 250, 1100), items.map { it.weightGrams })
        assertEquals(listOf(400L, 800L, 150L), items.map { it.unitPrice!!.pence })
        assertEquals(listOf(200L, 200L, 165L), items.map { it.price.pence })
    }

    @Test
    fun `a quantity line that fits no item is reported`() {
        val stray = parse("2 @ £1.10\nTOTAL 0.00")
        assertTrue(stray.items.isEmpty())
        assertTrue(stray.warnings.any { "quantity" in it }, stray.warnings.toString())
    }

    // ---- totals ----

    @Test
    fun `total labels`() {
        val lines = listOf(
            "TOTAL 1.00", "Total £1.00", "TOTAL TO PAY £1.00", "BALANCE DUE £1.00", "TO PAY 1.00",
            "AMOUNT DUE 1.00", "TOTAL: £1.00", "GRAND TOTAL 1.00", "TOTAL (1 ITEMS) 1.00",
        )
        for (line in lines) {
            assertEquals(Money(100), parse("MILK 1.00\n$line").total, line)
        }
    }

    @Test
    fun `a product starting with total is an item not the total`() {
        val draft = parse("TOTAL GREEK YOGURT 1.50\nTOTAL 1.50")
        assertEquals(listOf("TOTAL GREEK YOGURT"), draft.items.map { it.rawName })
        assertEquals(Money(150), draft.total)
    }

    @Test
    fun `subtotal and savings lines are not the total`() {
        val draft = parse(
            """
            MILK 1.00
            SUBTOTAL 1.00
            SUB TOTAL 1.00
            TOTAL SAVINGS 0.50
            YOU SAVED £0.50
            TOTAL 0.50
            """,
        )
        assertEquals(Money(50), draft.total)
        assertEquals(1, draft.items.size)
        assertEquals(Money.ZERO, draft.items.single().discount)
    }

    @Test
    fun `nothing after the total is read as an item`() {
        val draft = parse(
            """
            MILK 1.00
            TOTAL 1.00
            LATE LINE 5.00
            """,
        )
        assertEquals(1, draft.items.size)
    }

    @Test
    fun `two different totals give a warning and the first wins`() {
        val draft = parse("MILK 1.00\nTOTAL 1.00\nTOTAL 2.00")
        assertEquals(Money(100), draft.total)
        assertTrue(draft.warnings.any { "more than one total" in it })
    }

    @Test
    fun `missing total and date are reported as warnings`() {
        val draft = parse("MILK 1.00")
        assertNull(draft.total)
        assertNull(draft.date)
        assertTrue(draft.warnings.any { "total" in it })
        assertTrue(draft.warnings.any { "date" in it })
        assertEquals(ReceiptCheck.NoTotal, ReceiptChecks.check(draft))
    }

    @Test
    fun `payment lines are not items even when the total is missing`() {
        val draft = parse(
            """
            MILK 1.00
            VISA DEBIT 1.00
            CARD 1.00
            CASH 5.00
            CHANGE DUE 4.00
            """,
        )
        assertEquals(listOf("MILK"), draft.items.map { it.rawName })
    }

    @Test
    fun `an item with card in its name is still an item`() {
        val draft = parse("GREETING CARD 1.50\nTOTAL 1.50")
        assertEquals(listOf("GREETING CARD"), draft.items.map { it.rawName })
    }

    @Test
    fun `a lone E at the end of a name stays in the name`() {
        val draft = parse("VITAMIN E 3.50\nTOTAL 3.50")
        assertEquals("VITAMIN E", draft.items.single().rawName)
    }

    // ---- dates ----

    @Test
    fun `date and time on one line`() {
        val draft = parse("12 Oct 2026 14:32\nMILK 1.00\nTOTAL 1.00")
        assertEquals(LocalDate.of(2026, 10, 12), draft.date)
        assertEquals(1, draft.items.size)
    }

    @Test
    fun `a date at the bottom after the total is still found`() {
        val draft = parse("MILK 1.00\nTOTAL 1.00\n12-10-26 14.32 TILL 3")
        assertEquals(LocalDate.of(2026, 10, 12), draft.date)
    }

    @Test
    fun `the first date wins`() {
        val draft = parse("12/10/2026\nMILK 1.00\nTOTAL 1.00\n13/10/2026")
        assertEquals(LocalDate.of(2026, 10, 12), draft.date)
    }

    // ---- noise ----

    @Test
    fun `header and footer noise is not read as items`() {
        val draft = parse(
            """
            TESCO EXTRA
            Welwyn Garden City AL7 1ZZ
            Tel: 01707 123456
            VAT Reg No: GB 220 4302 31
            www.tesco.com
            TILL 03  CASHIER 118
            5012345678900
            MILK 1.00
            TOTAL 1.00
            Thank you for shopping with us
            """,
        )
        assertEquals(listOf("MILK"), draft.items.map { it.rawName })
    }

    @Test
    fun `vat summary lines before the total are noise`() {
        val draft = parse(
            """
            MILK 1.00
            A 20.00% VAT 0.17
            VAT 0.17
            TOTAL 1.00
            """,
        )
        assertEquals(1, draft.items.size)
    }

    @Test
    fun `lines without enough letters are not items`() {
        val draft = parse("--------- 1.00\n* 2.00\nX 3.00\nMILK 1.00\nTOTAL 1.00")
        assertEquals(listOf("MILK"), draft.items.map { it.rawName })
    }

    @Test
    fun `blank and whitespace only rows are skipped`() {
        val draft = ReceiptParsers.parse(
            listOf(ReceiptRow(""), ReceiptRow("   "), ReceiptRow("MILK   1.00"), ReceiptRow("TOTAL 1.00")),
            ReceiptSource.PHOTO,
        )
        assertEquals(1, draft.items.size)
    }

    @Test
    fun `parsing is deterministic`() {
        val a = ReceiptParsers.parseText(ReceiptFixtures.TESCO, ReceiptSource.PHOTO)
        val b = ReceiptParsers.parseText(ReceiptFixtures.TESCO, ReceiptSource.PHOTO)
        assertEquals(a, b)
    }

    @Test
    fun `random garbage never throws and keeps the sign and confidence invariants`() {
        val random = kotlin.random.Random(99)
        val pieces = listOf(
            "MILK", "TOTAL", "£", "1.45", "0.50-", "-0.30", "2 @", "x", "kg", "@", "/kg", "12/10/26", "TESCO",
            "Clubcard Price", "3 FOR", " ", "  ", "A", "*", "O.5O", "l.25", "VAT", "CARD", "0.452", "1,25", "-", ".",
        )
        repeat(3000) {
            val rows = List(random.nextInt(0, 12)) {
                ReceiptRow(List(random.nextInt(0, 8)) { pieces[random.nextInt(pieces.size)] }.joinToString(" "))
            }
            val draft = ReceiptParsers.parse(rows, ReceiptSource.PHOTO)
            for (item in draft.items) {
                assertTrue(!item.price.isNegative, "price $item")
                assertTrue(item.discount.pence <= 0, "discount $item")
                assertTrue(item.confidence in 0.1f..1f, "confidence $item")
                assertTrue(item.quantity >= 1, "quantity $item")
            }
        }
    }

    @Test
    fun `very long lines are handled quickly`() {
        val long = "1.5 ".repeat(400) + "0.50"
        val start = System.nanoTime()
        ReceiptParsers.parseText("$long\n" + "A".repeat(2000) + " 1.00\nTOTAL 1.00", ReceiptSource.PHOTO)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000, "took too long")
    }
}
