package io.github.matteopi.budgeting.backup

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvExportTest {
    private val account = Account(id = 1, name = "Current", type = AccountType.CURRENT)
    private val category = Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)

    @Test
    fun `header only when there are no transactions`() {
        val csv = CsvExport.transactionsToCsv(emptyList(), listOf(account), listOf(category))
        assertEquals("Date,Account,Category,Label,Note,Amount,Kind", csv)
    }

    @Test
    fun `formats a simple row`() {
        val tx = Transaction(
            id = 1,
            accountId = 1,
            amount = Money(-320),
            date = LocalDate.of(2026, 9, 28),
            categoryId = 1,
            label = "Tesco",
            note = "",
            kind = TxKind.NORMAL,
        )
        val csv = CsvExport.transactionsToCsv(listOf(tx), listOf(account), listOf(category))
        assertEquals(
            "Date,Account,Category,Label,Note,Amount,Kind\n2026-09-28,Current,Groceries,Tesco,,-£3.20,NORMAL",
            csv,
        )
    }

    @Test
    fun `quotes a field containing a comma`() {
        val tx = Transaction(
            id = 1,
            accountId = 1,
            amount = Money(1000),
            date = LocalDate.of(2026, 9, 28),
            categoryId = null,
            label = "Tesco, Camden",
            kind = TxKind.RECONCILIATION,
        )
        val csv = CsvExport.transactionsToCsv(listOf(tx), listOf(account), emptyList())
        val row = csv.lines()[1]
        // The comma inside the label must not be mistaken for a field separator: still 7 fields.
        assertEquals(7, splitCsvRow(row).size)
        assertEquals("Tesco, Camden", splitCsvRow(row)[3])
    }

    @Test
    fun `doubles internal quotes and wraps the field in quotes`() {
        val tx = Transaction(
            id = 1,
            accountId = 1,
            amount = Money(1000),
            date = LocalDate.of(2026, 9, 28),
            categoryId = null,
            label = "Plain",
            note = "Weekly \"shop\"",
            kind = TxKind.NORMAL,
        )
        val csv = CsvExport.transactionsToCsv(listOf(tx), listOf(account), emptyList())
        val row = csv.lines()[1]
        assertEquals("\"Weekly \"\"shop\"\"\"", row.split(",Plain,")[1].substringBefore(",£10.00"))
        assertEquals("Weekly \"shop\"", splitCsvRow(row)[4])
    }

    /** A tiny RFC-4180 splitter, only for asserting round-trip in these tests. */
    private fun splitCsvRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                inQuotes && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields += field.toString(); field.clear() }
                else -> field.append(c)
            }
            i++
        }
        fields += field.toString()
        return fields
    }

    @Test
    fun `sorts by date then creation time`() {
        val later = Transaction(
            id = 1,
            accountId = 1,
            amount = Money(-100),
            date = LocalDate.of(2026, 9, 28),
            createdAtEpochMillis = 2,
            categoryId = 1,
            label = "Second",
            kind = TxKind.NORMAL,
        )
        val earlier = Transaction(
            id = 2,
            accountId = 1,
            amount = Money(-200),
            date = LocalDate.of(2026, 9, 1),
            createdAtEpochMillis = 1,
            categoryId = 1,
            label = "First",
            kind = TxKind.NORMAL,
        )
        val csv = CsvExport.transactionsToCsv(listOf(later, earlier), listOf(account), listOf(category))
        val lines = csv.lines()
        assertEquals(3, lines.size)
        assertEquals(true, lines[1].contains("First"))
        assertEquals(true, lines[2].contains("Second"))
    }

    @Test
    fun `unknown account or category id falls back to blank`() {
        val tx = Transaction(
            id = 1,
            accountId = 99,
            amount = Money(-100),
            date = LocalDate.of(2026, 9, 28),
            categoryId = 99,
            label = "Orphan",
            kind = TxKind.NORMAL,
        )
        val csv = CsvExport.transactionsToCsv(listOf(tx), emptyList(), emptyList())
        assertEquals("Date,Account,Category,Label,Note,Amount,Kind\n2026-09-28,,,Orphan,,-£1.00,NORMAL", csv)
    }
}
