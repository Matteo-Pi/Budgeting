package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiptChecksTest {

    private fun draft(total: Money?, vararg items: DraftItem) =
        ReceiptDraft(Store.OTHER, null, items.toList(), total, ReceiptSource.PHOTO)

    private fun item(price: Long, discount: Long = 0) =
        DraftItem("X", price = Money(price), discount = Money(discount))

    @Test
    fun `items with discounts that add up to the total match`() {
        assertEquals(
            ReceiptCheck.Matches,
            ReceiptChecks.check(draft(Money(1250), item(1000, -100), item(350))),
        )
    }

    @Test
    fun `items adding up to too much give a positive difference`() {
        assertEquals(
            ReceiptCheck.Mismatch(Money(50)),
            ReceiptChecks.check(draft(Money(1200), item(1000, -100), item(350))),
        )
    }

    @Test
    fun `items adding up to too little give a negative difference`() {
        assertEquals(
            ReceiptCheck.Mismatch(Money(-300)),
            ReceiptChecks.check(draft(Money(1550), item(1000, -100), item(350))),
        )
    }

    @Test
    fun `no total gives NoTotal`() {
        assertEquals(ReceiptCheck.NoTotal, ReceiptChecks.check(draft(null, item(100))))
    }

    @Test
    fun `no items and a zero total match`() {
        assertEquals(ReceiptCheck.Matches, ReceiptChecks.check(draft(Money.ZERO)))
    }

    @Test
    fun `items total is price plus discount`() {
        assertEquals(Money(1250), ReceiptChecks.itemsTotal(draft(null, item(1000, -100), item(350))))
    }
}
