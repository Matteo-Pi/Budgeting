package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SantanderStatementTest {

    private val fixture = SantanderStatementFixture

    private fun parse(chunks: List<PdfTextChunk>): SantanderParseResult = SantanderStatement.parse(PdfCells.merge(chunks))

    private fun statement(chunks: List<PdfTextChunk>): ParsedStatement =
        (parse(chunks) as? SantanderParseResult.Success)?.statement
            ?: fail("Expected a statement but got ${parse(chunks)}")

    private fun unreadable(chunks: List<PdfTextChunk>): String =
        (parse(chunks) as? SantanderParseResult.Unreadable)?.reason
            ?: fail("Expected the statement to be unreadable")

    private val granularities = mapOf(
        "cells" to { fixture.cells() },
        "glyphs" to { fixture.glyphs() },
        "words" to { fixture.words() },
    )

    private val cleanedDescriptions = listOf(
        "From A Tester",
        "TRANSFER TO ALEX TESTER",
        "REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678",
        "JO BLOGGS, ref pizza",
        "ALEX TESTER, ref Transfer",
        "BOULDER WORLD",
        "UNION SHOP",
        "MORRISONS LEEDS",
        "UNION SHOP",
        "SAINSBURYS S/MKTS",
        "SQ *CORNER CAFE",
        "MORRISONS LEEDS",
        "TESCO STORES 1234",
        "TESCO STORES 5678",
        "UNION SHOP",
        "UNION SHOP",
        "UNION SHOP",
        "NYX*PHOTOBOOTH",
        "SAM SMITH, ref tickets",
    )

    // ---- the fixture ------------------------------------------------------------------------------------

    @Test
    fun `the fixture reads to the same statement at every granularity`() {
        val reference = statement(fixture.cells())
        for ((name, chunks) in granularities) {
            assertEquals(reference, statement(chunks()), "statement from $name")
        }
    }

    @Test
    fun `the fixture gives every row with its amount and balance and no problems`() {
        for ((name, chunks) in granularities) {
            val statement = statement(chunks())

            assertEquals(19, statement.lines.size, name)
            assertEquals(emptyList(), statement.problems, name)
            statement.lines.forEachIndexed { i, line ->
                val (rawDescription, pence, balance) = fixture.expectedRows[i]
                assertEquals(Money(pence), line.row.amount, "$name: amount of row $i")
                assertEquals(Money(balance), line.row.balance, "$name: balance of row $i")
                assertEquals(rawDescription.replace(" ,", ","), line.rawDescription, "$name: description of row $i")
                assertEquals(i, line.row.rowIndex, "$name: row index")
            }
        }
    }

    @Test
    fun `the fixture's period, balances, totals and holder come from the summary`() {
        for ((name, chunks) in granularities) {
            val statement = statement(chunks())

            assertEquals(LocalDate.of(2026, 9, 10), statement.periodStart, name)
            assertEquals(LocalDate.of(2026, 10, 10), statement.periodEnd, name)
            assertEquals(Money(fixture.OPENING_PENCE), statement.openingBalance, name)
            assertEquals(Money(fixture.CLOSING_PENCE), statement.closingBalance, name)
            assertEquals(Money(fixture.TOTAL_IN_PENCE), statement.totalIn, name)
            assertEquals(Money(fixture.TOTAL_OUT_PENCE), statement.totalOut, name)
            assertEquals("MISS ALEX TESTER", statement.accountHolder, name)
        }
    }

    @Test
    fun `card rows are dated by the card date and keep the posted date`() {
        val lines = statement(fixture.cells()).lines
        val tesco = lines.first { it.row.description == "TESCO STORES 1234" }

        assertEquals(LocalDate.of(2026, 10, 8), tesco.row.date)
        assertEquals(LocalDate.of(2026, 10, 9), tesco.postedDate)

        // Faster payments and transfers have no card date: the posted date is the date.
        val billPayment = lines[3]
        assertEquals(LocalDate.of(2026, 10, 2), billPayment.row.date)
        assertEquals(billPayment.row.date, billPayment.postedDate)
    }

    @Test
    fun `a card date after the posted date is not believed`() {
        val tampered = fixture.cells().map {
            if (it.text == "BOULDER WORLD (VIA GOOGLE PAY), ON 05-10-2026") it.copy(text = "BOULDER WORLD (VIA GOOGLE PAY), ON 07-10-2026") else it
        }
        val line = statement(tampered).lines[5]
        assertEquals(LocalDate.of(2026, 10, 6), line.row.date)
    }

    @Test
    fun `a row that wraps onto two lines has its whole description`() {
        val line = statement(fixture.cells()).lines[4]

        assertEquals(
            "BILL PAYMENT VIA FASTER PAYMENT TO ALEX TESTER REFERENCE Transfer, MANDATE NO 0003",
            line.rawDescription,
        )
        assertEquals("ALEX TESTER, ref Transfer", line.row.description)
        assertEquals(Money(-3_000), line.row.amount)
        assertEquals(Money(6_783), line.row.balance)
    }

    @Test
    fun `descriptions are cleaned`() {
        assertEquals(cleanedDescriptions, statement(fixture.cells()).lines.map { it.row.description })
    }

    @Test
    fun `moves between the user's own accounts are flagged`() {
        val flagged = statement(fixture.cells()).lines.mapIndexedNotNull { i, line -> if (line.likelyTransfer) i else null }
        // The faster payment from "A Tester", the transfer to ALEX TESTER, the regular transfer to the
        // savings account and the bill payment to ALEX TESTER; JO BLOGGS and SAM SMITH are other people.
        assertEquals(listOf(0, 1, 2, 4), flagged)
    }

    @Test
    fun `the average balance table on page 2 is not read as a row`() {
        val statement = statement(fixture.cells())
        assertTrue(statement.lines.none { it.rawDescription.contains("Average", ignoreCase = true) })
        assertEquals("From A Tester", statement.lines.first().row.description)
    }

    // ---- problems -----------------------------------------------------------------------------------------

    @Test
    fun `a balance that does not follow from the amount is reported against its row`() {
        // 24.19 -> 24.18 on the 10th Oct UNION SHOP row (row 17).
        val tampered = fixture.cells().map { if (it.page == 2 && it.text == "24.19") it.copy(text = "24.18") else it }
        val statement = statement(tampered)

        assertContains(statement.problems, "Row 17 (10 Oct, UNION SHOP): the balance doesn't follow from the amount")
        // The amounts themselves still add up, so nothing else is wrong.
        assertTrue(statement.problems.none { it.contains("adds up") || it.contains("add up") })
        // The row is still read, in the right direction (position decided it).
        assertEquals(Money(-77), statement.lines[16].row.amount)
        assertEquals(Money(2_418), statement.lines[16].row.balance)
    }

    @Test
    fun `a wrong amount is caught by the totals`() {
        // 17.31 -> 17.30: the running balance and both totals disagree.
        val tampered = fixture.cells().map { if (it.page == 2 && it.text == "17.31") it.copy(text = "17.30") else it }
        val problems = statement(tampered).problems

        assertTrue(problems.any { it.contains("doesn't follow") }, problems.toString())
        assertTrue(problems.any { it.contains("The money out on the rows adds up to") }, problems.toString())
        assertTrue(problems.any { it.contains("The transactions don't add up") }, problems.toString())
    }

    @Test
    fun `a missing row is caught by the balance chain and the totals`() {
        // Drop the 4th Oct row altogether (its date, both description lines, amount and balance).
        val tampered = fixture.cells().filter { cell ->
            val dropped = fixture.cells().first { it.text == ", MANDATE NO 0003" }
            !(cell.page == 2 && cell.y in (dropped.y - 9f)..(dropped.y + 1f))
        }
        val statement = statement(tampered)

        assertEquals(18, statement.lines.size)
        assertTrue(statement.problems.any { it.contains("doesn't follow") }, statement.problems.toString())
        assertTrue(statement.problems.any { it.contains("The money out on the rows adds up to £455.21") })
    }

    // ---- periods and pages --------------------------------------------------------------------------------

    @Test
    fun `a December to January period gets the right year for each row`() {
        val moved = fixture.cells().map { cell ->
            val text = cell.text
            val changed = when {
                Regex("""^\d+(st|nd|rd|th) Sep$""").matches(text) -> text.removeSuffix("Sep") + "Dec"
                Regex("""^\d+(st|nd|rd|th) Oct$""").matches(text) -> text.removeSuffix("Oct") + "Jan"
                text.startsWith("Your transactions") -> "Your transactions 10th Dec 2026 to 10th Jan 2027"
                else -> text.replace("-10-2026", "-01-2027")
            }
            cell.copy(text = changed)
        }
        val statement = statement(moved)

        assertEquals(LocalDate.of(2026, 12, 10), statement.periodStart)
        assertEquals(LocalDate.of(2027, 1, 10), statement.periodEnd)
        assertEquals(19, statement.lines.size)
        assertEquals(emptyList(), statement.problems)
        assertEquals(LocalDate.of(2026, 12, 24), statement.lines[0].postedDate)
        assertEquals(LocalDate.of(2026, 12, 29), statement.lines[2].postedDate)
        assertEquals(LocalDate.of(2027, 1, 2), statement.lines[3].postedDate)
        // BOULDER WORLD: posted 6 Jan, paid 5 Jan.
        assertEquals(LocalDate.of(2027, 1, 6), statement.lines[5].postedDate)
        assertEquals(LocalDate.of(2027, 1, 5), statement.lines[5].row.date)
    }

    @Test
    fun `a period inside one year uses that year, including an impossible date being skipped`() {
        val leap = fixture.cells().map { cell ->
            when {
                cell.text.startsWith("Your transactions") -> cell.copy(text = "Your transactions 1st Feb 2027 to 28th Feb 2027")
                cell.text == "2nd Oct" -> cell.copy(text = "30th Feb")
                else -> cell
            }
        }
        val statement = statement(leap)

        assertEquals(LocalDate.of(2027, 2, 1), statement.periodStart)
        assertEquals(18, statement.lines.size, "the impossible date is not a row")
        assertTrue(statement.problems.any { it.contains("Couldn't read the date") }, statement.problems.toString())
    }

    private fun cell(text: String, x: Float, y: Float, page: Int) =
        PdfTextChunk(text, x, y, x + text.length * fixture.CHAR_WIDTH, fixture.FONT_SIZE, page)

    private fun right(text: String, right: Float, y: Float, page: Int) = cell(text, right - text.length * fixture.CHAR_WIDTH, y, page)

    /** The fixture with its table split after the 10th row: a "carried forward" row, then a new page with the header again. */
    private fun twoPageTable(withDecoyAtTopOfPage3: Boolean): List<PdfTextChunk> {
        val all = fixture.cells()
        val page2 = all.filter { it.page == 2 }
        val splitY = page2.first { it.text == "8th Oct" }.y - 1f
        val kept = page2.filter { it.y < splitY }
        val moved = page2.filter { it.y >= splitY }.map { it.copy(y = it.y - splitY + 79f, page = 3) }
        val carriedY = kept.maxOf { it.y } + 9.5f

        val out = mutableListOf<PdfTextChunk>()
        out += all.filter { it.page == 1 }
        out += kept
        out += cell("7th Oct", 54.1f, carriedY, 2)
        out += cell("Balance carried forward", 116f, carriedY, 2)
        out += right("40.82", 548.6f, carriedY, 2)
        out += cell("Continued on next page", 300f, 800f, 2)
        if (withDecoyAtTopOfPage3) {
            out += cell("5th Oct", 54.1f, 30f, 3)
            out += cell("DECOY ROW", 116f, 30f, 3)
            out += right("1.00", 502f, 30f, 3)
            out += right("2.00", 548.6f, 30f, 3)
        }
        out += cell("Date", 54.1f, 60f, 3)
        out += cell("Description", 116f, 60f, 3)
        out += cell("Money in", 427.3f, 60f, 3)
        out += cell("Money out", 469.7f, 60f, 3)
        out += cell("£ Balance", 517.9f, 60f, 3)
        out += cell("7th Oct", 54.1f, 69.5f, 3)
        out += cell("Balance brought forward", 116f, 69.5f, 3)
        out += right("40.82", 548.6f, 69.5f, 3)
        out += moved
        return out
    }

    @Test
    fun `a table that continues on another page is read through its repeated header`() {
        val statement = statement(twoPageTable(withDecoyAtTopOfPage3 = false))

        assertEquals(emptyList(), statement.problems)
        assertEquals(19, statement.lines.size)
        assertEquals(cleanedDescriptions, statement.lines.map { it.row.description })
        assertEquals(fixture.expectedRows.map { Money(it.second) }, statement.lines.map { it.row.amount })
        assertEquals(Money(fixture.CLOSING_PENCE), statement.closingBalance)
    }

    @Test
    fun `cells on a later page are ignored until its header row appears`() {
        val statement = statement(twoPageTable(withDecoyAtTopOfPage3 = true))

        assertEquals(19, statement.lines.size)
        assertTrue(statement.lines.none { it.rawDescription.contains("DECOY") })
        assertEquals(emptyList(), statement.problems)
    }

    @Test
    fun `a balance brought forward that differs from the balance before it is reported`() {
        val all = twoPageTable(withDecoyAtTopOfPage3 = false).map {
            if (it.page == 3 && it.text == "40.82" && it.y < 75f) it.copy(text = "40.83") else it
        }
        val problems = statement(all).problems

        assertTrue(problems.any { it.contains("brought forward") && it.contains("£40.83") }, problems.toString())
    }

    // ---- direction, money formats -------------------------------------------------------------------------

    /** A hand-built page-2 table: the summary-less statement the position rules and money formats are tested on. */
    private class Table {
        val cells = mutableListOf<PdfTextChunk>()
        private var y = 446.4f

        private fun put(text: String, x: Float, y: Float) {
            cells += PdfTextChunk(text, x, y, x + text.length * 3.5f, 7f, 2)
        }

        private fun putRight(text: String, right: Float, y: Float) = put(text, right - text.length * 3.5f, y)

        fun start(moneyOutHeaderX: Float = 469.7f, moneyInHeaderX: Float = 427.3f): Table {
            put("Santander", 300f, 20f)
            put("Your transactions 10th Sep 2026 to 10th Oct 2026", 54.1f, 437f)
            put("Date", 54.1f, y)
            put("Description", 116f, y)
            put("Money in", moneyInHeaderX, y)
            put("Money out", moneyOutHeaderX, y)
            put("£ Balance", 517.9f, y)
            return this
        }

        fun opening(balance: String): Table {
            y += 9.5f
            put("10th Sep", 54.1f, y)
            put("Opening Balance", 116f, y)
            putRight(balance, 548.6f, y)
            return this
        }

        fun row(date: String, description: String, moneyIn: String? = null, moneyOut: String? = null, balance: String? = null): Table {
            y += 9.5f
            put(date, 54.1f, y)
            put(description, 116f, y)
            moneyIn?.let { putRight(it, 455.6f, y) }
            moneyOut?.let { putRight(it, 502f, y) }
            balance?.let { putRight(it, 548.6f, y) }
            return this
        }
    }

    @Test
    fun `without a balance to follow the column an amount sits in decides the direction`() {
        val chunks = Table().start()
            .row("2nd Oct", "SHOP ONE", moneyOut = "20.00", balance = "80.00")
            .row("3rd Oct", "WAGES", moneyIn = "50.00", balance = "130.00")
            .cells
        val statement = statement(chunks)

        assertEquals(listOf(Money(-2_000), Money(5_000)), statement.lines.map { it.row.amount })
        assertEquals(emptyList(), statement.problems, "the second row's balance follows from the first")
    }

    @Test
    fun `the balance chain overrules the column when the amount matches the change`() {
        // The amount is drawn in the Money in column, but the balance fell by exactly that much.
        val chunks = Table().start().opening("100.00")
            .row("2nd Oct", "SHOP ONE", moneyIn = "20.00", balance = "80.00")
            .cells
        val statement = statement(chunks)

        assertEquals(Money(-2_000), statement.lines.single().row.amount)
        assertEquals(emptyList(), statement.problems)
    }

    @Test
    fun `an amount the balance does not explain falls back to its column and is reported`() {
        val chunks = Table().start().opening("100.00")
            .row("6th Oct", "UNION SHOP (VIA GOOGLE PAY), ON 05-10-2026", moneyOut = "10.00", balance = "95.00")
            .cells
        val statement = statement(chunks)

        assertEquals(Money(-1_000), statement.lines.single().row.amount)
        assertEquals(
            listOf("Row 1 (6 Oct, UNION SHOP): the balance doesn't follow from the amount"),
            statement.problems,
        )
    }

    @Test
    fun `an amount exactly between the two columns cannot be placed`() {
        val chunks = Table().start(moneyOutHeaderX = 423.8f).row("2nd Oct", "SHOP ONE", moneyOut = "20.00", balance = "80.00").cells
        // The Money out header now ends where the Money in header does, so the column cannot say which it is.
        val reason = unreadable(chunks)
        assertContains(reason, "money in")
    }

    @Test
    fun `pounds signs, thousands commas and overdrawn balances are read`() {
        val chunks = Table().start().opening("£1,000.00")
            .row("2nd Oct", "BIG SHOP", moneyOut = "£1,250.50", balance = "250.50-")
            .row("3rd Oct", "REFUND", moneyIn = "£10.50", balance = "-£240.00")
            .row("4th Oct", "MORE", moneyOut = "10.00", balance = "250.00 OD")
            .cells
        val statement = statement(chunks)

        assertEquals(listOf(Money(-125_050), Money(1_050), Money(-1_000)), statement.lines.map { it.row.amount })
        assertEquals(listOf(Money(-25_050), Money(-24_000), Money(-25_000)), statement.lines.map { it.row.balance })
        assertEquals(emptyList(), statement.problems)
        assertEquals(Money(100_000), statement.openingBalance)
    }

    @Test
    fun `a row with no balance is reported and left out`() {
        val chunks = Table().start().opening("100.00")
            .row("2nd Oct", "SHOP ONE", moneyOut = "20.00", balance = "80.00")
            .row("3rd Oct", "SHOP TWO", moneyOut = "5.00")
            .row("4th Oct", "SHOP THREE", moneyOut = "10.00", balance = "65.00")
            .cells
        val statement = statement(chunks)

        assertEquals(listOf("SHOP ONE", "SHOP THREE"), statement.lines.map { it.row.description })
        assertTrue(statement.problems.any { it.contains("3rd Oct SHOP TWO") }, statement.problems.toString())
    }

    @Test
    fun `closing and opening balances fall back to the table when there is no summary`() {
        val chunks = Table().start().opening("100.00")
            .row("2nd Oct", "SHOP ONE", moneyOut = "20.00", balance = "80.00")
            .row("10th Oct", "Balance carried forward to next statement:", balance = "80.00")
            .row("11th Oct", "AFTER THE END", moneyOut = "1.00", balance = "79.00")
            .cells
        val statement = statement(chunks)

        assertEquals(Money(10_000), statement.openingBalance)
        assertEquals(Money(8_000), statement.closingBalance)
        assertNull(statement.totalIn)
        assertNull(statement.accountHolder)
        assertEquals(1, statement.lines.size, "the table ends at the carried forward row")
        assertEquals(emptyList(), statement.problems)
    }

    // ---- unreadable and not Santander --------------------------------------------------------------------

    @Test
    fun `text without the Santander markers is not a Santander statement`() {
        assertFalse(SantanderStatement.looksLikeSantander(emptyList()))
        assertFalse(SantanderStatement.looksLikeSantander(PdfCells.merge(listOf(PdfTextChunk("Invoice 42", 10f, 10f, 60f, 10f, 1)))))
        // A statement from somewhere else with a similar table.
        val other = fixture.cells().filterNot { it.text.contains("santander", ignoreCase = true) }
        assertFalse(SantanderStatement.looksLikeSantander(other))
        // Santander letterhead without a transactions section.
        val summaryOnly = fixture.cells().filter { it.page == 1 }
        assertFalse(SantanderStatement.looksLikeSantander(summaryOnly))
        assertTrue(SantanderStatement.looksLikeSantander(PdfCells.merge(fixture.glyphs())))
        assertTrue(SantanderStatement.looksLikeSantander(fixture.cells()))
    }

    @Test
    fun `a statement without its transactions table is unreadable`() {
        assertContains(unreadable(fixture.cells().filter { it.page == 1 }), "transactions")
        assertContains(unreadable(emptyList()), "transactions")
        // The heading without the column headers.
        assertContains(unreadable(Table().start().cells.filter { it.text != "Money in" }), "transactions")
    }

    @Test
    fun `a table with no rows is unreadable`() {
        val reason = unreadable(Table().start().opening("0.00").cells)
        assertContains(reason, "no transaction rows")
    }

    // ---- account holder ------------------------------------------------------------------------------------

    @Test
    fun `the account holder is also read from the page 2 header`() {
        val withoutPage1Name = fixture.cells().filterNot { it.page == 1 && (it.text.trim() == "Account name" || it.text == fixture.ACCOUNT_HOLDER) }
        val statement = statement(withoutPage1Name)

        assertEquals("MISS ALEX TESTER", statement.accountHolder)
        assertEquals(listOf(0, 1, 2, 4), statement.lines.mapIndexedNotNull { i, line -> if (line.likelyTransfer) i else null })
    }

    // ---- cleanDescription ------------------------------------------------------------------------------------

    @Test
    fun `card payments through a wallet become the merchant`() {
        assertEquals("UNION SHOP", SantanderStatement.cleanDescription("UNION SHOP (VIA GOOGLE PAY), ON 05-10-2026"))
        assertEquals("UNION SHOP", SantanderStatement.cleanDescription("UNION SHOP (VIA APPLE PAY), ON 05-10-2026"))
        assertEquals("UNION SHOP", SantanderStatement.cleanDescription("UNION SHOP (VIA SAMSUNG PAY), ON 05-10-2026"))
        assertEquals("TESCO STORES 1234", SantanderStatement.cleanDescription("TESCO STORES 1234 (VIA GOOGLE PAY), ON 08-10-2026"))
    }

    @Test
    fun `a dash left after the merchant is removed`() {
        assertEquals(
            "MORRISONS LEEDS",
            SantanderStatement.cleanDescription("MORRISONS LEEDS - (VIA GOOGLE PAY), ON 06-10-2026"),
        )
    }

    @Test
    fun `classic card payments become the merchant`() {
        assertEquals(
            "CORNER CAFE LTD",
            SantanderStatement.cleanDescription("CARD PAYMENT TO CORNER CAFE LTD,7.75 GBP, RATE 1.00/GBP, ON 08-10-2026"),
        )
        assertEquals(
            "TESCO STORES 3012",
            SantanderStatement.cleanDescription("CARD PAYMENT TO TESCO STORES 3012 ON 15-09-2026"),
        )
    }

    @Test
    fun `bill payments keep the payee and the reference as written`() {
        val expected = "JO BLOGGS, ref pizza"
        assertEquals(
            expected,
            SantanderStatement.cleanDescription("BILL PAYMENT VIA FASTER PAYMENT TO JO BLOGGS REFERENCE pizza , MANDATE NO 0002"),
        )
        assertEquals(
            expected,
            SantanderStatement.cleanDescription("BILL PAYMENT VIA FASTER PAYMENT TO JO BLOGGS REFERENCE pizza, MANDATE NO 0002"),
        )
        assertEquals(
            "COUNCIL TAX",
            SantanderStatement.cleanDescription("BILL PAYMENT VIA FASTER PAYMENT TO COUNCIL TAX, MANDATE NO 12"),
        )
    }

    @Test
    fun `standing orders and direct debits are tidied the same way`() {
        assertEquals(
            "LANDLORD LTD, ref Rent Oct",
            SantanderStatement.cleanDescription("STANDING ORDER VIA FASTER PAYMENT TO LANDLORD LTD REFERENCE Rent Oct, MANDATE NO 7"),
        )
        assertEquals(
            "VODAFONE LTD, ref 123456789",
            SantanderStatement.cleanDescription("DIRECT DEBIT PAYMENT TO VODAFONE LTD REF 123456789, MANDATE NO 0012"),
        )
    }

    @Test
    fun `faster payments received show the payer and only a reference that is not an account number`() {
        assertEquals("From A Tester", SantanderStatement.cleanDescription("FASTER PAYMENTS RECEIPT REF.11223344556677 FROM A Tester"))
        assertEquals("From J SMITH, ref Rent", SantanderStatement.cleanDescription("FASTER PAYMENTS RECEIPT REF.Rent FROM J SMITH"))
        assertEquals("From MUM", SantanderStatement.cleanDescription("FASTER PAYMENTS RECEIPT FROM MUM"))
        assertEquals("From J SMITH, ref RENT", SantanderStatement.cleanDescription("FASTER PAYMENTS RECEIPT FROM J SMITH REF RENT"))
    }

    @Test
    fun `transfers are kept without the mandate number`() {
        assertEquals("TRANSFER TO ALEX TESTER", SantanderStatement.cleanDescription("TRANSFER TO ALEX TESTER"))
        assertEquals("TRANSFER FROM JO BLOGGS", SantanderStatement.cleanDescription("TRANSFER FROM JO BLOGGS, MANDATE NO 3"))
        assertEquals(
            "REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678",
            SantanderStatement.cleanDescription("REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678, MANDATE NO 1"),
        )
    }

    @Test
    fun `anything else is only tidied`() {
        assertEquals("SOME ODD THING", SantanderStatement.cleanDescription("  SOME   ODD  THING "))
        assertEquals("CASH WITHDRAWAL, ref abc", SantanderStatement.cleanDescription("CASH WITHDRAWAL , ref abc"))
        assertEquals("", SantanderStatement.cleanDescription("   "))
    }

    // ---- likelyTransfer ----------------------------------------------------------------------------------------

    private val holder = "MISS ALEX TESTER"

    @Test
    fun `transfer descriptions are moves between accounts whoever the holder is`() {
        assertTrue(SantanderStatement.likelyTransfer("TRANSFER TO ALEX TESTER", null))
        assertTrue(SantanderStatement.likelyTransfer("TRANSFER FROM SOMEONE ELSE", holder))
        assertTrue(SantanderStatement.likelyTransfer("REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678, MANDATE NO 1", holder))
        assertTrue(SantanderStatement.likelyTransfer("REGULAR TRANSFER PAYMENT FROM ACCOUNT SAVING R12345678", holder))
        assertFalse(SantanderStatement.likelyTransfer("TRANSFERWISE LTD", holder))
    }

    @Test
    fun `a payment to or from the account holder is a move between accounts`() {
        val bill = "BILL PAYMENT VIA FASTER PAYMENT TO %s REFERENCE Transfer, MANDATE NO 0003"
        assertTrue(SantanderStatement.likelyTransfer(bill.format("ALEX TESTER"), holder))
        assertTrue(SantanderStatement.likelyTransfer(bill.format("alex tester"), holder), "case does not matter")
        assertTrue(SantanderStatement.likelyTransfer(bill.format("MR ALEX TESTER"), holder), "titles are dropped")
        assertTrue(SantanderStatement.likelyTransfer(bill.format("A TESTER"), holder), "an initial stands for the name")
        assertTrue(SantanderStatement.likelyTransfer(bill.format("ALEX T"), holder))
        assertTrue(SantanderStatement.likelyTransfer(bill.format("TESTER A"), holder))
        assertTrue(SantanderStatement.likelyTransfer("FASTER PAYMENTS RECEIPT REF.11223344556677 FROM A Tester", holder))
        assertTrue(SantanderStatement.likelyTransfer("STANDING ORDER VIA FASTER PAYMENT TO ALEX TESTER REFERENCE Save", holder))
    }

    @Test
    fun `other people are not the account holder`() {
        val bill = "BILL PAYMENT VIA FASTER PAYMENT TO %s REFERENCE pizza, MANDATE NO 0002"
        assertFalse(SantanderStatement.likelyTransfer(bill.format("JO BLOGGS"), holder))
        assertFalse(SantanderStatement.likelyTransfer(bill.format("SAM SMITH"), holder))
        assertFalse(SantanderStatement.likelyTransfer(bill.format("ALEX SMITH"), holder), "a first name alone is not enough")
        assertFalse(SantanderStatement.likelyTransfer(bill.format("ALEXANDER TESTER"), holder))
        assertFalse(SantanderStatement.likelyTransfer(bill.format("A T"), holder), "initials alone prove nothing")
        assertFalse(SantanderStatement.likelyTransfer("FASTER PAYMENTS RECEIPT REF.123 FROM SAM SMITH", holder))
        assertFalse(SantanderStatement.likelyTransfer("UNION SHOP (VIA GOOGLE PAY), ON 05-10-2026", holder))
        assertFalse(SantanderStatement.likelyTransfer(bill.format("ALEX TESTER"), null), "no holder, no match")
    }
}
