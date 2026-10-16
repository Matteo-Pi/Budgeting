package io.github.matteopi.budgeting.ui.importer

import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.ParsedStatement
import io.github.matteopi.budgeting.core.importer.StatementLine
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatementImportRulesTest {

    // ---- PDF or CSV ----------------------------------------------------------------------------------

    @Test
    fun `a PDF is recognised by the type the system reports`() {
        assertTrue(PdfDetection.isPdf("application/pdf", ByteArray(0)))
        assertTrue(PdfDetection.isPdf("APPLICATION/PDF", "hello".toByteArray()))
    }

    @Test
    fun `a PDF is recognised by its first bytes when the type is generic`() {
        val header = "%PDF-1.7\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue(PdfDetection.isPdf(null, header))
        assertTrue(PdfDetection.isPdf("application/octet-stream", header))
        assertTrue(PdfDetection.isPdf("*/*", header.copyOf(PdfDetection.HEADER_LENGTH)))
    }

    @Test
    fun `a CSV is not a PDF`() {
        assertFalse(PdfDetection.isPdf("text/csv", "Date,Description\n".toByteArray()))
        assertFalse(PdfDetection.isPdf(null, ByteArray(0)))
        assertFalse(PdfDetection.isPdf(null, "%PD".toByteArray()), "too short to be the PDF header")
        assertFalse(PdfDetection.isPdf(null, " %PDF".toByteArray()))
    }

    // ---- move between my accounts --------------------------------------------------------------------

    private fun account(id: Long, name: String, archived: Boolean = false) =
        Account(id = id, name = name, type = AccountType.CURRENT, archived = archived)

    @Test
    fun `a group is a move between accounts by default only if every row looks like one`() {
        assertTrue(ImportTransfers.defaultOn(listOf(true)))
        assertTrue(ImportTransfers.defaultOn(listOf(true, true, true)))
        assertFalse(ImportTransfers.defaultOn(listOf(true, false)))
        assertFalse(ImportTransfers.defaultOn(listOf(false)))
        assertFalse(ImportTransfers.defaultOn(emptyList()))
    }

    @Test
    fun `the other account can be any live account except the one being imported into`() {
        val accounts = listOf(account(1, "Current"), account(2, "Savings"), account(3, "Old", archived = true), account(4, "Second"))

        assertEquals(listOf("Savings", "Second"), ImportTransfers.otherAccounts(accounts, importingInto = 1).map { it.name })
        assertEquals(listOf("Current", "Savings", "Second"), ImportTransfers.otherAccounts(accounts, importingInto = null).map { it.name })
    }

    @Test
    fun `a stale choice of other account falls back to not in the app`() {
        val accounts = listOf(account(1, "Current"), account(2, "Savings"), account(3, "Old", archived = true))

        assertEquals(2L, ImportTransfers.validOtherAccountId(2, accounts, importingInto = 1))
        assertNull(ImportTransfers.validOtherAccountId(null, accounts, importingInto = 1))
        assertNull(ImportTransfers.validOtherAccountId(1, accounts, importingInto = 1), "not the account being imported into")
        assertNull(ImportTransfers.validOtherAccountId(3, accounts, importingInto = 1), "not an archived account")
        assertNull(ImportTransfers.validOtherAccountId(99, accounts, importingInto = 1), "not an account that is gone")
    }

    // ---- wording -----------------------------------------------------------------------------------------

    private fun statement(
        start: LocalDate = LocalDate.of(2026, 9, 10),
        end: LocalDate = LocalDate.of(2026, 10, 10),
        rows: Int = 19,
        closing: Money? = Money(1_479),
    ) = ParsedStatement(
        periodStart = start,
        periodEnd = end,
        accountHolder = null,
        openingBalance = Money.ZERO,
        closingBalance = closing,
        totalIn = null,
        totalOut = null,
        lines = List(rows) { i ->
            StatementLine(ParsedRow(end, Money(-100), "ROW $i", null, i), "ROW $i", end, false)
        },
        problems = emptyList(),
    )

    @Test
    fun `the statement card says what was read`() {
        assertEquals(
            "Santander statement · 10 Sep – 10 Oct 2026 · 19 transactions · closing balance £14.79",
            StatementText.cardLine(statement()),
        )
    }

    @Test
    fun `the statement card handles one transaction, a period over New Year and no closing balance`() {
        assertEquals(
            "Santander statement · 10 Dec 2026 – 10 Jan 2027 · 1 transaction",
            StatementText.cardLine(statement(LocalDate.of(2026, 12, 10), LocalDate.of(2027, 1, 10), rows = 1, closing = null)),
        )
    }

    @Test
    fun `the balance check says when the app matches the statement`() {
        val check = StatementText.balanceCheck(Money(1_479), Money(1_479), LocalDate.of(2026, 10, 10))

        assertTrue(check.matches)
        assertEquals("Matches your statement: £14.79 on 10 Oct ✓", check.message)
    }

    @Test
    fun `the balance check says how far off the app is`() {
        val above = StatementText.balanceCheck(Money(1_479), Money(5_000), LocalDate.of(2026, 10, 10))
        val below = StatementText.balanceCheck(Money(1_479), Money(1_000), LocalDate.of(2026, 10, 10))

        assertFalse(above.matches)
        assertEquals(
            "Your statement says £14.79 on 10 Oct; the app says £50.00 (£35.21 different). Transactions you entered " +
                "by hand that are also on the statement, or rows you unticked, would explain it.",
            above.message,
        )
        assertTrue("(£4.79 different)" in below.message, below.message)
    }

    @Test
    fun `an overdrawn statement balance is shown with its sign`() {
        val check = StatementText.balanceCheck(Money(-2_500), Money(-2_500), LocalDate.of(2026, 9, 1))
        assertEquals("Matches your statement: -£25.00 on 1 Sep ✓", check.message)
    }
}
