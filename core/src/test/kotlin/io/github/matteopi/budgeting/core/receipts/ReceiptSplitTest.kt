package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceiptSplitTest {

    private fun item(category: Long?, price: Long, discount: Long = 0) =
        SplitItem(category, Money(price), Money(discount))

    private fun split(total: Long, vararg items: SplitItem) = ReceiptSplit.byCategory(items.toList(), Money(total))

    private fun List<SplitPart>.byCategory() = associate { it.categoryId to it.amount.pence }

    @Test
    fun `items group by category and become negative amounts`() {
        val parts = split(1000, item(1, 300), item(2, 500), item(1, 200))
        assertEquals(mapOf<Long?, Long>(1L to -500L, 2L to -500L), parts.byCategory())
        assertEquals(-1000L, parts.sumOf { it.amount.pence })
    }

    @Test
    fun `discounts reduce the category they belong to`() {
        val parts = split(750, item(1, 300, -50), item(2, 500))
        assertEquals(mapOf<Long?, Long>(1L to -250L, 2L to -500L), parts.byCategory())
    }

    @Test
    fun `a bag charge missing from the items goes on the largest group`() {
        val parts = split(1010, item(1, 300), item(2, 700))
        assertEquals(mapOf<Long?, Long>(1L to -300L, 2L to -710L), parts.byCategory())
    }

    @Test
    fun `items adding up to more than the total reduce the largest group`() {
        val parts = split(990, item(1, 300), item(2, 700))
        assertEquals(mapOf<Long?, Long>(1L to -300L, 2L to -690L), parts.byCategory())
    }

    @Test
    fun `uncategorised items form a null group`() {
        val parts = split(800, item(1, 300), item(null, 500))
        assertEquals(mapOf<Long?, Long>(1L to -300L, null to -500L), parts.byCategory())
    }

    @Test
    fun `only uncategorised items give a single null group with the whole total`() {
        val parts = split(800, item(null, 300), item(null, 450))
        assertEquals(listOf(SplitPart(null, Money(-800))), parts)
    }

    @Test
    fun `no items gives one uncategorised part for the whole total`() {
        assertEquals(listOf(SplitPart(null, Money(-1250))), split(1250))
    }

    @Test
    fun `a tie for largest goes to the categorised group`() {
        val parts = split(1100, item(null, 500), item(7, 500))
        assertEquals(mapOf<Long?, Long>(7L to -600L, null to -500L), parts.byCategory())
    }

    @Test
    fun `a tie between categories goes to the one that appears first`() {
        val parts = split(1100, item(4, 500), item(3, 500))
        assertEquals(mapOf<Long?, Long>(4L to -600L, 3L to -500L), parts.byCategory())
    }

    @Test
    fun `groups that cancel out to zero are dropped`() {
        val parts = split(500, item(1, 500), item(2, 200, -200))
        assertEquals(listOf(SplitPart(1, Money(-500))), parts)
    }

    @Test
    fun `a zero total with nothing spent keeps one zero part`() {
        assertEquals(listOf(SplitPart(null, Money.ZERO)), split(0))
    }

    @Test
    fun `result is ordered largest spend first`() {
        val parts = split(1000, item(1, 100), item(2, 600), item(3, 300))
        assertEquals(listOf<Long?>(2, 3, 1), parts.map { it.categoryId })
    }

    @Test
    fun `when items match the total every group is exactly its own spend`() {
        val parts = split(1234, item(1, 100, -10), item(2, 600), item(1, 544))
        assertEquals(mapOf<Long?, Long>(1L to -634L, 2L to -600L), parts.byCategory())
    }

    @Test
    fun `random receipts always sum exactly to minus the total`() {
        val random = Random(2026)
        repeat(3000) { n ->
            val count = random.nextInt(0, 13)
            val items = List(count) {
                val price = random.nextLong(0, 5000)
                val discount = if (random.nextInt(4) == 0) -random.nextLong(0, price + 1) else 0L
                val category = random.nextInt(0, 5).let { if (it == 4) null else it.toLong() }
                item(category, price, discount)
            }
            val itemSum = items.sumOf { it.spend.pence }
            val total = when (random.nextInt(3)) {
                0 -> itemSum
                1 -> itemSum + random.nextLong(-20, 21)
                else -> random.nextLong(0, 40_000)
            }
            val parts = ReceiptSplit.byCategory(items, Money(total))

            assertEquals(-total, parts.sumOf { it.amount.pence }, "case $n: $items total $total")
            assertTrue(parts.isNotEmpty(), "case $n")
            assertEquals(parts.size, parts.map { it.categoryId }.toSet().size, "one part per category, case $n")
            assertTrue(
                parts.all { it.categoryId == null || items.any { i -> i.categoryId == it.categoryId } },
                "case $n: invented category",
            )
            assertTrue(
                parts.zipWithNext().all { (a, b) -> a.amount <= b.amount },
                "case $n: not largest first",
            )
            if (parts.size > 1) assertTrue(parts.none { it.amount.isZero }, "case $n: zero part kept")
            if (total == itemSum && items.isNotEmpty()) {
                val expected = items.groupBy { it.categoryId }.mapValues { -it.value.sumOf { i -> i.spend.pence } }
                    .filterValues { it != 0L }
                if (expected.isNotEmpty()) assertEquals(expected, parts.byCategory(), "case $n: exact split")
            }
        }
    }
}
