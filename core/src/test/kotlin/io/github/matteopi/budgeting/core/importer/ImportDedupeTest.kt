package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ImportDedupeTest {

    private fun row(date: LocalDate, pence: Long, description: String, rowIndex: Int = 0) = ParsedRow(
        date = date, amount = Money(pence), description = description, balance = null, rowIndex = rowIndex,
    )

    @Test
    fun `distinct rows get distinct keys`() {
        val rows = listOf(
            row(LocalDate.of(2026, 9, 1), -1250, "TESCO STORES"),
            row(LocalDate.of(2026, 9, 2), -1250, "TESCO STORES"),
        )
        val keys = ImportDedupe.importKeys(1, rows)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `identical rows in the same file get an incrementing occurrence and so distinct keys`() {
        val rows = listOf(
            row(LocalDate.of(2026, 9, 1), -350, "GREGGS"),
            row(LocalDate.of(2026, 9, 1), -350, "GREGGS"),
            row(LocalDate.of(2026, 9, 1), -350, "GREGGS"),
        )
        val keys = ImportDedupe.importKeys(1, rows)
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun `same key is stable and reproducible`() {
        val rows = listOf(row(LocalDate.of(2026, 9, 1), -1250, "TESCO STORES"))
        val keys1 = ImportDedupe.importKeys(1, rows)
        val keys2 = ImportDedupe.importKeys(1, rows)
        assertEquals(keys1, keys2)
    }

    @Test
    fun `different account gives a different key for the same row`() {
        val rows = listOf(row(LocalDate.of(2026, 9, 1), -1250, "TESCO STORES"))
        val keysA = ImportDedupe.importKeys(1, rows)
        val keysB = ImportDedupe.importKeys(2, rows)
        assertNotEquals(keysA, keysB)
    }

    @Test
    fun `classify flags a row whose import key already exists as ALREADY_IMPORTED`() {
        val rows = listOf(row(LocalDate.of(2026, 9, 1), -1250, "TESCO STORES"))
        val keys = ImportDedupe.importKeys(1, rows)
        val existing = listOf(
            Transaction(id = 1, accountId = 1, amount = Money(-1250), date = LocalDate.of(2026, 9, 1), categoryId = 1, importKey = keys[0]),
        )
        val statuses = ImportDedupe.classify(1, rows, keys, existing)
        assertEquals(listOf(RowStatus.ALREADY_IMPORTED), statuses)
    }

    @Test
    fun `classify flags a same-amount manual entry within two days as POSSIBLE_DUPLICATE`() {
        val rows = listOf(row(LocalDate.of(2026, 9, 5), -1250, "TESCO STORES"))
        val keys = ImportDedupe.importKeys(1, rows)
        val existing = listOf(
            Transaction(id = 1, accountId = 1, amount = Money(-1250), date = LocalDate.of(2026, 9, 3), categoryId = 1, kind = TxKind.NORMAL),
        )
        val statuses = ImportDedupe.classify(1, rows, keys, existing)
        assertEquals(listOf(RowStatus.POSSIBLE_DUPLICATE), statuses)
    }

    @Test
    fun `classify does not flag a manual entry more than two days away`() {
        val rows = listOf(row(LocalDate.of(2026, 9, 10), -1250, "TESCO STORES"))
        val keys = ImportDedupe.importKeys(1, rows)
        val existing = listOf(
            Transaction(id = 1, accountId = 1, amount = Money(-1250), date = LocalDate.of(2026, 9, 3), categoryId = 1, kind = TxKind.NORMAL),
        )
        val statuses = ImportDedupe.classify(1, rows, keys, existing)
        assertEquals(listOf(RowStatus.NEW), statuses)
    }

    @Test
    fun `classify never matches the same existing transaction to two rows`() {
        val rows = listOf(
            row(LocalDate.of(2026, 9, 5), -1250, "TESCO STORES", rowIndex = 0),
            row(LocalDate.of(2026, 9, 5), -1250, "TESCO STORES", rowIndex = 1),
        )
        val keys = ImportDedupe.importKeys(1, rows)
        val existing = listOf(
            Transaction(id = 1, accountId = 1, amount = Money(-1250), date = LocalDate.of(2026, 9, 5), categoryId = 1, kind = TxKind.NORMAL),
        )
        val statuses = ImportDedupe.classify(1, rows, keys, existing)
        assertEquals(listOf(RowStatus.POSSIBLE_DUPLICATE, RowStatus.NEW), statuses)
    }

    @Test
    fun `classify ignores a different account's transactions`() {
        val rows = listOf(row(LocalDate.of(2026, 9, 5), -1250, "TESCO STORES"))
        val keys = ImportDedupe.importKeys(1, rows)
        val existing = listOf(
            Transaction(id = 1, accountId = 2, amount = Money(-1250), date = LocalDate.of(2026, 9, 5), categoryId = 1, kind = TxKind.NORMAL),
        )
        val statuses = ImportDedupe.classify(1, rows, keys, existing)
        assertEquals(listOf(RowStatus.NEW), statuses)
    }

    @Test
    fun `classify flags a transfer leg with the same amount within two days as POSSIBLE_DUPLICATE`() {
        // The other account's statement was imported first and left this leg behind (no import key).
        val rows = listOf(row(LocalDate.of(2026, 10, 4), 3_000, "TRANSFER FROM ALEX TESTER"))
        val keys = ImportDedupe.importKeys(1, rows)
        val existing = listOf(
            Transaction(
                id = 1, accountId = 1, amount = Money(3_000), date = LocalDate.of(2026, 10, 4), categoryId = null,
                kind = TxKind.TRANSFER, transferGroupId = "group", importBatchId = 9,
            ),
        )
        assertEquals(listOf(RowStatus.POSSIBLE_DUPLICATE), ImportDedupe.classify(1, rows, keys, existing))

        val twoDaysLater = existing.map { it.copy(date = LocalDate.of(2026, 10, 6)) }
        assertEquals(listOf(RowStatus.POSSIBLE_DUPLICATE), ImportDedupe.classify(1, rows, keys, twoDaysLater))

        val threeDaysLater = existing.map { it.copy(date = LocalDate.of(2026, 10, 7)) }
        assertEquals(listOf(RowStatus.NEW), ImportDedupe.classify(1, rows, keys, threeDaysLater))
    }

    @Test
    fun `classify ignores transfer legs that have another amount, another account or an import key`() {
        val rows = listOf(row(LocalDate.of(2026, 10, 4), 3_000, "TRANSFER FROM ALEX TESTER"))
        val keys = ImportDedupe.importKeys(1, rows)
        val leg = Transaction(
            id = 1, accountId = 1, amount = Money(3_000), date = LocalDate.of(2026, 10, 4), categoryId = null,
            kind = TxKind.TRANSFER, transferGroupId = "group",
        )

        assertEquals(listOf(RowStatus.NEW), ImportDedupe.classify(1, rows, keys, listOf(leg.copy(amount = Money(3_001)))))
        assertEquals(listOf(RowStatus.NEW), ImportDedupe.classify(1, rows, keys, listOf(leg.copy(accountId = 2))))
        // A leg an earlier import brought in itself (it has a key of its own) is not a manual entry.
        assertEquals(listOf(RowStatus.NEW), ImportDedupe.classify(1, rows, keys, listOf(leg.copy(importKey = "other-key"))))
        // Other kinds never match.
        assertEquals(
            listOf(RowStatus.NEW),
            ImportDedupe.classify(1, rows, keys, listOf(leg.copy(kind = TxKind.OPENING_BALANCE))),
        )
    }

    @Test
    fun `a transfer leg matches at most one row`() {
        val rows = listOf(
            row(LocalDate.of(2026, 10, 4), 3_000, "TRANSFER FROM ALEX TESTER", rowIndex = 0),
            row(LocalDate.of(2026, 10, 4), 3_000, "TRANSFER FROM ALEX TESTER", rowIndex = 1),
        )
        val keys = ImportDedupe.importKeys(1, rows)
        val existing = listOf(
            Transaction(
                id = 1, accountId = 1, amount = Money(3_000), date = LocalDate.of(2026, 10, 4), categoryId = null,
                kind = TxKind.TRANSFER, transferGroupId = "group",
            ),
        )
        assertEquals(listOf(RowStatus.POSSIBLE_DUPLICATE, RowStatus.NEW), ImportDedupe.classify(1, rows, keys, existing))
    }
}
