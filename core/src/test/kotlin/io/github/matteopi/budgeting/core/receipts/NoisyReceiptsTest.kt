package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OCR-style input: fragments with positions, look-alike letters in amounts, missing pound signs. */
class NoisyReceiptsTest {

    /** Each row is (left fragment, right fragment or null); rows are 30 units apart with jitter. */
    private fun ocr(vararg rows: Pair<String, String?>, random: Random = Random(11)): List<ReceiptLine> =
        rows.flatMapIndexed { i, (left, right) ->
            val top = 100f + i * 30f
            buildList {
                add(ReceiptLine(left, top = top + random.nextInt(-3, 4), left = 10f, right = 200f))
                if (right != null) add(ReceiptLine(right, top = top + random.nextInt(-3, 4), left = 300f, right = 360f))
            }
        }

    private val noisyTesco = arrayOf(
        "TESCO" to "EXTRA",
        "12/10/2026" to "14:32",
        "MILK SEMI SKIMMED 2PT" to "£1.45",
        "HOVIS MEDIUM 800G" to "£l.30",
        "Clubcard Price" to "-£O.30",
        "BANANAS LOOSE" to null,
        "0.452 kg @ £1.20/kg" to "0.54",
        "CHICKEN BREAST 650G" to "4.5O",
        "TOTAL" to "£7.49",
        "VISA CONTACTLESS" to "£7.49",
    )

    @Test
    fun `fragments with positions are regrouped and parsed`() {
        val draft = ReceiptParsers.parseLines(ocr(*noisyTesco), ReceiptSource.PHOTO)
        assertEquals(Store.TESCO, draft.store)
        assertEquals(LocalDate.of(2026, 10, 12), draft.date)
        assertEquals(Money(749), draft.total)
        assertEquals(
            listOf("MILK SEMI SKIMMED 2PT", "HOVIS MEDIUM 800G", "BANANAS LOOSE", "CHICKEN BREAST 650G"),
            draft.items.map { it.rawName },
        )
        assertEquals(listOf(145L, 130L, 54L, 450L), draft.items.map { it.price.pence })
        assertEquals(Money(-30), draft.items[1].discount)
        assertEquals(452, draft.items[2].weightGrams)
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
        assertEquals(ReceiptSource.PHOTO, draft.source)
    }

    @Test
    fun `amounts read through look alike letters get lower confidence`() {
        val items = ReceiptParsers.parseLines(ocr(*noisyTesco), ReceiptSource.PHOTO).items
        assertEquals(1f, items[0].confidence)
        assertTrue(items[1].confidence < 1f, "l read as 1")
        assertTrue(items[3].confidence < 1f, "O read as 0")
        assertEquals(1f, items[2].confidence)
    }

    @Test
    fun `a discount whose amount has a look alike letter still attaches`() {
        val draft = ReceiptParsers.parseLines(ocr(*noisyTesco), ReceiptSource.PHOTO)
        assertEquals(Money(-30), draft.items[1].discount)
    }

    @Test
    fun `shuffled fragments give the same draft`() {
        val lines = ocr(*noisyTesco)
        val expected = ReceiptParsers.parseLines(lines, ReceiptSource.PHOTO)
        repeat(10) { seed ->
            assertEquals(expected, ReceiptParsers.parseLines(lines.shuffled(Random(seed)), ReceiptSource.PHOTO))
        }
    }

    @Test
    fun `missing pound signs and E for pound are fine`() {
        val text = """
            SAINSBURY'S
            MILK 2L  E1.55
            BREAD  0.65
            Nectar Price -0.10
            BALANCE DUE 2.10
        """.trimIndent()
        val draft = ReceiptParsers.parseText(text, ReceiptSource.SHARED_TEXT)
        assertEquals(Store.SAINSBURYS, draft.store)
        assertEquals(listOf(155L, 65L), draft.items.map { it.price.pence })
        assertEquals(Money(210), draft.total)
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
    }

    @Test
    fun `look alike letters in the total and the label are forgiven`() {
        val text = """
            TESCO
            MILK 2PT 1.45
            BREAD 0.65
            T0TAL £2.l0
        """.trimIndent()
        val draft = ReceiptParsers.parseText(text, ReceiptSource.PHOTO)
        assertEquals(Money(210), draft.total)
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft))
    }

    @Test
    fun `an S or B read in an amount is fixed and flagged`() {
        val text = """
            MILK 2PT 1.4S
            BREAD 0.B5
            TOTAL 2.30
        """.trimIndent()
        val draft = ReceiptParsers.parseText(text, ReceiptSource.PHOTO)
        assertEquals(listOf(145L, 85L), draft.items.map { it.price.pence })
        assertTrue(draft.items.all { it.confidence < 1f })
    }

    @Test
    fun `comma as the decimal separator is read`() {
        val draft = ReceiptParsers.parseText("MILK 2PT 1,45\nTOTAL 1,45", ReceiptSource.PHOTO)
        assertEquals(Money(145), draft.items.single().price)
        assertEquals(Money(145), draft.total)
    }

    @Test
    fun `odd looking names get lower confidence`() {
        val text = """
            M1LK SEMI 1.45
            ~~BREAD~~ 0.90
            CHEESE 2.00
            TOTAL 4.35
        """.trimIndent()
        val items = ReceiptParsers.parseText(text, ReceiptSource.PHOTO).items
        assertEquals(3, items.size)
        assertTrue(items[0].confidence < 1f, "digit inside a word")
        assertTrue(items[1].confidence < 1f, "stray symbols")
        assertEquals(1f, items[2].confidence)
    }

    @Test
    fun `a price of zero or an absurd price gets lower confidence`() {
        val items = ReceiptParsers.parseText("FREE GIFT 0.00\nSOFA 999.00\nMILK 1.00", ReceiptSource.PHOTO).items
        assertTrue(items[0].confidence < 1f)
        assertTrue(items[1].confidence < 1f)
        assertEquals(1f, items[2].confidence)
    }

    @Test
    fun `litre sizes are not mistaken for prices`() {
        val draft = ReceiptParsers.parseText("COKE ZERO 1.5l\nWATER 1.5l   0.85\nTOTAL 0.85", ReceiptSource.PHOTO)
        assertEquals(listOf("WATER 1.5l"), draft.items.map { it.rawName })
        assertEquals(Money(85), draft.items.single().price)
    }

    @Test
    fun `a missed price shows up as a mismatch`() {
        val text = """
            TESCO
            MILK 2PT 1.45
            BREAD
            CHEESE 2.00
            TOTAL 4.35
        """.trimIndent()
        val draft = ReceiptParsers.parseText(text, ReceiptSource.PHOTO)
        assertEquals(2, draft.items.size)
        assertEquals(ReceiptCheck.Mismatch(Money(-90)), ReceiptChecks.check(draft))
    }
}
