package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatementImportTest {

    @Test
    fun `guesses a SIGNED mapping from a realistic UK bank header`() {
        val text = """
            Date,Description,Amount,Balance
            01/09/2026,TESCO STORES 3012,-12.50,987.65
            03/09/2026,SALARY,1500.00,2487.65
        """.trimIndent() + "\n"
        val mapping = StatementImport.guessMapping(CsvParser.parse(text))

        assertTrue(mapping.hasHeader)
        assertEquals(0, mapping.dateColumn)
        assertEquals(1, mapping.descriptionColumn)
        assertEquals(AmountMode.SIGNED, mapping.amountMode)
        assertEquals(2, mapping.amountColumn)
        assertEquals(3, mapping.balanceColumn)
        assertEquals("dd/MM/yyyy", mapping.datePattern)
    }

    @Test
    fun `guesses a DEBIT_CREDIT mapping from money in and out columns`() {
        val text = """
            Transaction Date,Details,Money In,Money Out,Balance
            01/09/2026,TESCO STORES 3012,,12.50,987.65
            03/09/2026,SALARY,1500.00,,2487.65
        """.trimIndent() + "\n"
        val mapping = StatementImport.guessMapping(CsvParser.parse(text))

        assertTrue(mapping.hasHeader)
        assertEquals(AmountMode.DEBIT_CREDIT, mapping.amountMode)
        assertEquals(2, mapping.creditColumn)
        assertEquals(3, mapping.debitColumn)
        assertEquals(1, mapping.descriptionColumn)
    }

    @Test
    fun `guesses alternate header names paid in paid out narrative`() {
        val text = """
            Date,Narrative,Paid In,Paid Out,Balance
            15/09/2026,VODAFONE,,25.00,500.00
        """.trimIndent() + "\n"
        val mapping = StatementImport.guessMapping(CsvParser.parse(text))
        assertEquals(AmountMode.DEBIT_CREDIT, mapping.amountMode)
        assertEquals(2, mapping.creditColumn)
        assertEquals(3, mapping.debitColumn)
    }

    @Test
    fun `guesses yyyy-MM-dd date pattern`() {
        val text = "Date,Description,Amount\n2026-09-15,Tesco,-12.50\n2026-09-16,Shell,-30.00\n"
        val mapping = StatementImport.guessMapping(CsvParser.parse(text))
        assertEquals("yyyy-MM-dd", mapping.datePattern)
    }

    @Test
    fun `guesses a MMM month-name date pattern`() {
        val text = "Date,Description,Amount\n15 Sep 2026,Tesco,-12.50\n16 Sep 2026,Shell,-30.00\n"
        val mapping = StatementImport.guessMapping(CsvParser.parse(text))
        assertEquals("d MMM yyyy", mapping.datePattern)
        assertEquals(LocalDate.of(2026, 9, 15), java.time.LocalDate.parse(
            "15 Sep 2026", java.time.format.DateTimeFormatter.ofPattern(mapping.datePattern, java.util.Locale.ENGLISH),
        ))
    }

    @Test
    fun `guesses d MMM yyyy for single digit days too`() {
        val text = "Date,Description,Amount\n5 Sep 2026,Tesco,-12.50\n6 Sep 2026,Shell,-30.00\n"
        val mapping = StatementImport.guessMapping(CsvParser.parse(text))
        assertEquals("d MMM yyyy", mapping.datePattern)
    }

    @Test
    fun `parses SIGNED amounts in all supported formats`() {
        val text = """
            Date,Description,Amount
            01/09/2026,Big shop,"£1,234.50"
            02/09/2026,Coffee,-3.20
            03/09/2026,Refund,(12.50)
            04/09/2026,Card sale,12.50 CR
            05/09/2026,Card fee,12.50 DR
        """.trimIndent() + "\n"
        val table = CsvParser.parse(text)
        val mapping = StatementMapping(
            hasHeader = true, dateColumn = 0, datePattern = "dd/MM/yyyy",
            amountMode = AmountMode.SIGNED, amountColumn = 2, descriptionColumn = 1,
        )
        val outcome = StatementImport.parseRows(table, mapping)

        assertEquals(emptyList(), outcome.errors)
        assertEquals(5, outcome.rows.size)
        assertEquals(Money(123450), outcome.rows[0].amount)
        assertEquals(Money(-320), outcome.rows[1].amount)
        assertEquals(Money(-1250), outcome.rows[2].amount)
        assertEquals(Money(1250), outcome.rows[3].amount)
        assertEquals(Money(-1250), outcome.rows[4].amount)
    }

    @Test
    fun `parses DEBIT_CREDIT amount as credit minus debit`() {
        val table = CsvParser.parse(
            "Date,Description,In,Out\n01/09/2026,Salary,1500.00,\n02/09/2026,Rent,,600.00\n",
        )
        val mapping = StatementMapping(
            hasHeader = true, dateColumn = 0, datePattern = "dd/MM/yyyy",
            amountMode = AmountMode.DEBIT_CREDIT, creditColumn = 2, debitColumn = 3, descriptionColumn = 1,
        )
        val outcome = StatementImport.parseRows(table, mapping)

        assertEquals(emptyList(), outcome.errors)
        assertEquals(Money.pounds(1500), outcome.rows[0].amount)
        assertEquals(Money.pounds(-600), outcome.rows[1].amount)
    }

    @Test
    fun `unparseable date produces a row error and skips the row`() {
        val table = CsvParser.parse("Date,Description,Amount\nnot-a-date,Tesco,-1.00\n15/09/2026,Shell,-2.00\n")
        val mapping = StatementMapping(
            hasHeader = true, dateColumn = 0, datePattern = "dd/MM/yyyy",
            amountMode = AmountMode.SIGNED, amountColumn = 2, descriptionColumn = 1,
        )
        val outcome = StatementImport.parseRows(table, mapping)
        assertEquals(1, outcome.errors.size)
        assertEquals(1, outcome.errors[0].rowIndex)
        assertEquals(1, outcome.rows.size)
        assertEquals(LocalDate.of(2026, 9, 15), outcome.rows[0].date)
    }

    @Test
    fun `unparseable amount produces a row error`() {
        val table = CsvParser.parse("Date,Description,Amount\n15/09/2026,Tesco,garbage\n")
        val mapping = StatementMapping(
            hasHeader = true, dateColumn = 0, datePattern = "dd/MM/yyyy",
            amountMode = AmountMode.SIGNED, amountColumn = 2, descriptionColumn = 1,
        )
        val outcome = StatementImport.parseRows(table, mapping)
        assertEquals(1, outcome.errors.size)
        assertTrue(outcome.rows.isEmpty())
    }

    @Test
    fun `blank rows are skipped without error`() {
        val table = CsvParser.parse("Date,Description,Amount\n,,\n15/09/2026,Tesco,-1.00\n")
        val mapping = StatementMapping(
            hasHeader = true, dateColumn = 0, datePattern = "dd/MM/yyyy",
            amountMode = AmountMode.SIGNED, amountColumn = 2, descriptionColumn = 1,
        )
        val outcome = StatementImport.parseRows(table, mapping)
        assertEquals(0, outcome.errors.size)
        assertEquals(1, outcome.rows.size)
    }

    @Test
    fun `reads the optional balance column`() {
        val table = CsvParser.parse("Date,Description,Amount,Balance\n15/09/2026,Tesco,-1.00,£498.50\n")
        val mapping = StatementMapping(
            hasHeader = true, dateColumn = 0, datePattern = "dd/MM/yyyy",
            amountMode = AmountMode.SIGNED, amountColumn = 2, descriptionColumn = 1, balanceColumn = 3,
        )
        val outcome = StatementImport.parseRows(table, mapping)
        assertEquals(Money(49850), outcome.rows[0].balance)
    }

    @Test
    fun `no balance column means null balance`() {
        val table = CsvParser.parse("Date,Description,Amount\n15/09/2026,Tesco,-1.00\n")
        val mapping = StatementMapping(
            hasHeader = true, dateColumn = 0, datePattern = "dd/MM/yyyy",
            amountMode = AmountMode.SIGNED, amountColumn = 2, descriptionColumn = 1,
        )
        val outcome = StatementImport.parseRows(table, mapping)
        assertNull(outcome.rows[0].balance)
    }
}
