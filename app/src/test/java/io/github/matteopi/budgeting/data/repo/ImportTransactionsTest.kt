package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImportTransactionsTest {
    private val date = LocalDate.of(2026, 10, 4)
    private val row = ParsedRow(date, Money(-3_000), "ALEX TESTER, ref Transfer", Money(6_783), 4)

    private fun legs(
        selected: SelectedImportRow,
        otherOpening: LocalDate? = null,
    ) = ImportTransactions.forRow(
        accountId = 1,
        selected = selected,
        batchId = 7,
        nowMillis = 1_000L,
        transferGroupId = "group-1",
        otherAccountOpeningDate = otherOpening,
    )

    @Test
    fun `an ordinary row becomes one NORMAL transaction with its category`() {
        val result = legs(SelectedImportRow(row, "key-1", categoryId = 5))

        val tx = result.single()
        assertEquals(TxKind.NORMAL, tx.kind)
        assertEquals(1L, tx.accountId)
        assertEquals(Money(-3_000), tx.amount)
        assertEquals(date, tx.date)
        assertEquals(5L, tx.categoryId)
        assertEquals("ALEX TESTER, ref Transfer", tx.label)
        assertEquals(7L, tx.importBatchId)
        assertEquals("key-1", tx.importKey)
        assertNull(tx.transferGroupId)
    }

    @Test
    fun `a move to an account outside the app is a single uncategorised TRANSFER leg`() {
        val result = legs(SelectedImportRow(row, "key-1", categoryId = 5, transfer = ImportTransfer(otherAccountId = null)))

        val tx = result.single()
        assertEquals(TxKind.TRANSFER, tx.kind)
        assertEquals(1L, tx.accountId)
        assertEquals(Money(-3_000), tx.amount)
        assertNull(tx.categoryId, "a transfer has no category even if the group had one")
        assertEquals("group-1", tx.transferGroupId)
        assertEquals(7L, tx.importBatchId)
        assertEquals("key-1", tx.importKey)
    }

    @Test
    fun `a move to another account also gets the opposite leg there`() {
        val result = legs(SelectedImportRow(row, "key-1", categoryId = null, transfer = ImportTransfer(otherAccountId = 2)))

        assertEquals(2, result.size)
        val (own, opposite) = result
        assertEquals(1L, own.accountId)
        assertEquals(Money(-3_000), own.amount)
        assertEquals("key-1", own.importKey)

        assertEquals(2L, opposite.accountId)
        assertEquals(Money(3_000), opposite.amount)
        assertEquals(date, opposite.date)
        assertEquals(own.label, opposite.label)
        assertEquals(TxKind.TRANSFER, opposite.kind)
        assertNull(opposite.categoryId)
        assertEquals("group-1", opposite.transferGroupId)
        assertEquals(7L, opposite.importBatchId, "tagged with the batch so undoing it removes this leg too")
        assertNull(opposite.importKey, "it is not a row of the other account's statement")
    }

    @Test
    fun `money coming in puts the opposite leg out of the other account`() {
        val incoming = row.copy(amount = Money(50_000))
        val result = legs(SelectedImportRow(incoming, "key-1", null, ImportTransfer(2)))

        assertEquals(Money(50_000), result[0].amount)
        assertEquals(Money(-50_000), result[1].amount)
    }

    @Test
    fun `the opposite leg is skipped when the other account's opening balance already includes it`() {
        val selected = SelectedImportRow(row, "key-1", null, ImportTransfer(2))

        assertEquals(1, legs(selected, otherOpening = date.plusDays(1)).size)
        assertEquals(2, legs(selected, otherOpening = date).size, "on the opening date it is a new transaction")
        assertEquals(2, legs(selected, otherOpening = date.minusDays(30)).size)
        assertEquals(2, legs(selected, otherOpening = null).size, "no opening balance to double count")
    }

    @Test
    fun `a move to the account being imported into has no opposite leg`() {
        val result = legs(SelectedImportRow(row, "key-1", null, ImportTransfer(otherAccountId = 1)))

        assertEquals(1, result.size)
        assertNotNull(result.single().transferGroupId)
    }
}
