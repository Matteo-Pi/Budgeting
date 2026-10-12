package io.github.matteopi.budgeting.core.receipts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineGroupingTest {

    @Test
    fun `text only input passes through as one row per line`() {
        val rows = LineGrouping.rows(listOf(ReceiptLine("MILK  1.45"), ReceiptLine("TOTAL 1.45")))
        assertEquals(listOf("MILK 1.45", "TOTAL 1.45"), rows.map { it.text })
    }

    @Test
    fun `text only input keeps order and duplicates and drops blank lines`() {
        val rows = LineGrouping.rows(
            listOf(ReceiptLine("B"), ReceiptLine("  "), ReceiptLine("A"), ReceiptLine("B")),
        )
        assertEquals(listOf("B", "A", "B"), rows.map { it.text })
    }

    @Test
    fun `plain text is split into one row per non blank line`() {
        val rows = LineGrouping.rows("MILK 1.45\n\n  BREAD   0.90  \r\nTOTAL 2.35")
        assertEquals(listOf("MILK 1.45", "BREAD 0.90", "TOTAL 2.35"), rows.map { it.text })
    }

    @Test
    fun `fragments on the same baseline merge left to right`() {
        val rows = LineGrouping.rows(
            listOf(
                ReceiptLine("£1.45", top = 103f, left = 300f, right = 360f),
                ReceiptLine("MILK 2PT", top = 100f, left = 10f, right = 120f),
            ),
        )
        assertEquals(listOf("MILK 2PT £1.45"), rows.map { it.text })
    }

    @Test
    fun `rows are ordered top to bottom whatever the input order`() {
        val rows = LineGrouping.rows(
            listOf(
                ReceiptLine("TOTAL", top = 300f, left = 10f, right = 80f),
                ReceiptLine("MILK", top = 100f, left = 10f, right = 80f),
                ReceiptLine("BREAD", top = 200f, left = 10f, right = 80f),
            ),
        )
        assertEquals(listOf("MILK", "BREAD", "TOTAL"), rows.map { it.text })
    }

    @Test
    fun `fragments further apart than the tolerance are separate rows`() {
        val lines = listOf(
            ReceiptLine("A", top = 100f, left = 10f, right = 20f),
            ReceiptLine("B", top = 140f, left = 10f, right = 20f),
        )
        assertEquals(2, LineGrouping.rows(lines).size)
        assertEquals(1, LineGrouping.rows(lines, tolerance = 50f).size)
    }

    @Test
    fun `tolerance follows fragment height when heights are known`() {
        // Height 40, so tolerance 20: 15 apart merges, 30 apart does not.
        fun frag(text: String, top: Float) = ReceiptLine(text, top = top, left = 0f, right = 10f, bottom = top + 40f)
        assertEquals(1, LineGrouping.rows(listOf(frag("A", 100f), frag("B", 115f))).size)
        assertEquals(2, LineGrouping.rows(listOf(frag("A", 100f), frag("B", 130f))).size)
    }

    @Test
    fun `blank fragments are dropped and empty input gives no rows`() {
        assertTrue(LineGrouping.rows(emptyList<ReceiptLine>()).isEmpty())
        val rows = LineGrouping.rows(
            listOf(ReceiptLine("  ", top = 5f, left = 1f), ReceiptLine("X", top = 5f, left = 9f)),
        )
        assertEquals(listOf("X"), rows.map { it.text })
    }

    @Test
    fun `shuffled jittered fragments always regroup to the same rows`() {
        val random = Random(7)
        val expected = (0 until 25).map { "ITEM $it   £${it}.${(it * 7) % 100}".replace(Regex("""\.(\d)$"""), ".0$1") }
        val fragments = expected.flatMapIndexed { i, text ->
            val (name, price) = text.split("   ")
            val top = 100f + i * 40f
            listOf(
                ReceiptLine(name, top = top + random.nextInt(-4, 5), left = 10f, right = 150f),
                ReceiptLine(price, top = top + random.nextInt(-4, 5), left = 300f, right = 360f),
            )
        }
        repeat(20) { seed ->
            val rows = LineGrouping.rows(fragments.shuffled(Random(seed)))
            assertEquals(expected.map { it.replace("   ", " ") }, rows.map { it.text })
        }
    }
}
