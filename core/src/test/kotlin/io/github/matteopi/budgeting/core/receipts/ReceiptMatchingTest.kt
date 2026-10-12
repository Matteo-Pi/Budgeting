package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceiptMatchingTest {

    private val day = LocalDate.of(2026, 10, 12)

    private fun draft(total: Money? = Money(1284), date: LocalDate? = day) =
        ReceiptDraft(Store.TESCO, date, emptyList(), total, ReceiptSource.PHOTO)

    private fun tx(
        id: Long,
        pence: Long = -1284,
        date: LocalDate = day,
        account: Long = 1,
        kind: TxKind = TxKind.NORMAL,
    ) = Transaction(id = id, accountId = account, amount = Money(pence), date = date, categoryId = 1, kind = kind)

    private fun find(vararg txs: Transaction, accountId: Long? = null, draft: ReceiptDraft = draft()) =
        ReceiptMatching.findTransaction(draft, txs.toList(), accountId)?.id

    @Test
    fun `exact amount on the same day matches`() {
        assertEquals(1L, find(tx(1)))
    }

    @Test
    fun `one penny either way matches, two pence does not`() {
        assertEquals(1L, find(tx(1, pence = -1283)))
        assertEquals(1L, find(tx(1, pence = -1285)))
        assertNull(find(tx(1, pence = -1282)))
        assertNull(find(tx(1, pence = -1286)))
    }

    @Test
    fun `up to two days either side matches, three does not`() {
        assertEquals(1L, find(tx(1, date = day.minusDays(2))))
        assertEquals(1L, find(tx(1, date = day.plusDays(2))))
        assertNull(find(tx(1, date = day.minusDays(3))))
        assertNull(find(tx(1, date = day.plusDays(3))))
    }

    @Test
    fun `only normal spending transactions qualify`() {
        assertNull(find(tx(1, kind = TxKind.TRANSFER)))
        assertNull(find(tx(2, kind = TxKind.OPENING_BALANCE)))
        assertNull(find(tx(3, kind = TxKind.RECONCILIATION)))
        assertNull(find(tx(4, pence = 1284)))
    }

    @Test
    fun `same day beats same account`() {
        val sameDayOtherAccount = tx(1, account = 2)
        val nextDaySameAccount = tx(2, account = 1, date = day.plusDays(1))
        assertEquals(1L, find(nextDaySameAccount, sameDayOtherAccount, accountId = 1))
    }

    @Test
    fun `same account beats a smaller gap when neither is on the same day`() {
        val twoDaysSameAccount = tx(1, account = 1, date = day.minusDays(2))
        val oneDayOtherAccount = tx(2, account = 2, date = day.plusDays(1))
        assertEquals(1L, find(oneDayOtherAccount, twoDaysSameAccount, accountId = 1))
        assertEquals(2L, find(oneDayOtherAccount, twoDaysSameAccount, accountId = 2))
    }

    @Test
    fun `without an account preference the smallest gap wins`() {
        val twoDays = tx(1, date = day.minusDays(2))
        val oneDay = tx(2, date = day.plusDays(1))
        assertEquals(2L, find(twoDays, oneDay))
    }

    @Test
    fun `account preference applies among same day candidates`() {
        assertEquals(2L, find(tx(1, account = 1), tx(2, account = 2), accountId = 2))
        assertEquals(1L, find(tx(1, account = 1), tx(2, account = 2), accountId = 1))
    }

    @Test
    fun `an exact amount beats an off by a penny amount when everything else ties`() {
        assertEquals(2L, find(tx(1, pence = -1285), tx(2, pence = -1284)))
    }

    @Test
    fun `ties fall back to the lowest id`() {
        assertEquals(3L, find(tx(7), tx(3), tx(5)))
    }

    @Test
    fun `no total or no date or a non positive total gives null`() {
        assertNull(find(tx(1), draft = draft(total = null)))
        assertNull(find(tx(1), draft = draft(date = null)))
        assertNull(find(tx(1, pence = 0), draft = draft(total = Money.ZERO)))
    }

    @Test
    fun `no transactions gives null`() {
        assertNull(find())
    }
}
