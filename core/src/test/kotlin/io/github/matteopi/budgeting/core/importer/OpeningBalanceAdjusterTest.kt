package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpeningBalanceAdjusterTest {

    private val opening = Transaction(
        id = 1, accountId = 1, amount = Money.pounds(1000), date = LocalDate.of(2026, 9, 15),
        categoryId = null, kind = TxKind.OPENING_BALANCE,
    )

    private fun row(date: LocalDate, pence: Long) = ParsedRow(date, Money(pence), "desc", null, 0)

    @Test
    fun `no rows before the opening date means no adjustment`() {
        val imported = listOf(row(LocalDate.of(2026, 9, 16), -500))
        assertNull(OpeningBalanceAdjuster.adjust(opening, imported))
    }

    @Test
    fun `rows before the opening date are subtracted and the date moves earlier`() {
        val imported = listOf(
            row(LocalDate.of(2026, 9, 10), -1250), // before opening
            row(LocalDate.of(2026, 9, 12), 200),    // before opening
            row(LocalDate.of(2026, 9, 16), -300),   // after opening, not counted
        )
        val adjusted = OpeningBalanceAdjuster.adjust(opening, imported)
        requireNotNull(adjusted)
        assertEquals(Money.pounds(1000) - Money(-1250 + 200), adjusted.amount)
        assertEquals(LocalDate.of(2026, 9, 9), adjusted.date)
    }

    @Test
    fun `only the amount and date change`() {
        val imported = listOf(row(LocalDate.of(2026, 9, 1), -100))
        val adjusted = OpeningBalanceAdjuster.adjust(opening, imported)
        requireNotNull(adjusted)
        assertEquals(opening.id, adjusted.id)
        assertEquals(opening.accountId, adjusted.accountId)
        assertEquals(opening.kind, adjusted.kind)
    }
}
