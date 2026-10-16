package io.github.matteopi.budgeting.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.importer.PdfCells
import io.github.matteopi.budgeting.core.importer.PdfTextChunk
import io.github.matteopi.budgeting.core.importer.SantanderParseResult
import io.github.matteopi.budgeting.core.importer.SantanderStatement
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.testing.SantanderPdfFixture
import io.github.matteopi.budgeting.ui.importer.PdfReadException
import io.github.matteopi.budgeting.ui.importer.PdfStatementReader
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A statement drawn into a real PDF with `PdfDocument` (invented content, see [SantanderPdfFixture]) is read
 * back through PDFBox's text layer, merged into cells and parsed: every row, balance and check comes out as
 * the fixture says. This is what the import screen does with a PDF downloaded from the bank.
 */
@RunWith(AndroidJUnit4::class)
class SantanderPdfTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()

    private fun newFile(): File = File(context.cacheDir, "santander-test-${System.nanoTime()}.pdf").also { files += it }

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
    }

    /** The cells as text, for failure messages: the first thing to look at when a PDF reads differently on a device. */
    private fun describe(cells: List<PdfTextChunk>): String = cells.take(70).joinToString("\n") {
        "p${it.page} x=${"%.1f".format(it.x)}..${"%.1f".format(it.endX)} y=${"%.1f".format(it.y)} h=${it.height} '${it.text}'"
    }

    @Test
    fun theDrawnStatementIsReadRowForRowWithNoProblems() = runBlocking<Unit> {
        val file = SantanderPdfFixture.write(context, newFile())
        val cells = PdfCells.merge(PdfStatementReader.read(context, file))

        assertTrue(SantanderStatement.looksLikeSantander(cells), "not recognised as Santander:\n${describe(cells)}")
        val result = SantanderStatement.parse(cells)
        val statement = (result as? SantanderParseResult.Success)?.statement
            ?: fail("The statement was not read: $result\n${describe(cells)}")

        assertEquals(emptyList(), statement.problems, describe(cells))
        assertEquals(SantanderPdfFixture.rows.size, statement.lines.size, describe(cells))
        statement.lines.zip(SantanderPdfFixture.rows).forEachIndexed { i, (line, expected) ->
            assertEquals(Money(expected.pence), line.row.amount, "amount of row $i (${expected.cleaned})")
            assertEquals(Money(expected.balancePence), line.row.balance, "balance of row $i (${expected.cleaned})")
            assertEquals(expected.cleaned, line.row.description, "description of row $i")
            assertEquals(expected.date, line.row.date, "date of row $i (${expected.cleaned})")
            assertEquals(expected.postedDate, line.postedDate, "posted date of row $i (${expected.cleaned})")
            assertEquals(expected.likelyTransfer, line.likelyTransfer, "transfer flag of row $i (${expected.cleaned})")
            assertEquals(i, line.row.rowIndex)
        }

        assertEquals(SantanderPdfFixture.PERIOD_START, statement.periodStart)
        assertEquals(SantanderPdfFixture.PERIOD_END, statement.periodEnd)
        assertEquals(SantanderPdfFixture.ACCOUNT_HOLDER, statement.accountHolder)
        assertEquals(Money(SantanderPdfFixture.OPENING_PENCE), statement.openingBalance)
        assertEquals(Money(SantanderPdfFixture.CLOSING_PENCE), statement.closingBalance)
        assertEquals(Money(SantanderPdfFixture.TOTAL_IN_PENCE), statement.totalIn)
        assertEquals(Money(SantanderPdfFixture.TOTAL_OUT_PENCE), statement.totalOut)
    }

    @Test
    fun theWrappedRowAndTheDecoyTableAreReadRight() = runBlocking<Unit> {
        val file = SantanderPdfFixture.write(context, newFile())
        val cells = PdfCells.merge(PdfStatementReader.read(context, file))
        val statement = (SantanderStatement.parse(cells) as? SantanderParseResult.Success)?.statement
            ?: fail("The statement was not read:\n${describe(cells)}")

        val wrapped = statement.lines[4]
        assertEquals(
            "BILL PAYMENT VIA FASTER PAYMENT TO ALEX TESTER REFERENCE Transfer, MANDATE NO 0003",
            wrapped.rawDescription,
            describe(cells),
        )
        assertTrue(statement.lines.none { it.rawDescription.contains("Average") }, "the Average balance row is not a transaction")
    }

    @Test
    fun aStatementIsRecognisedFromItsFileAndAnInvoiceIsNot() = runBlocking<Unit> {
        val statement = SantanderPdfFixture.write(context, newFile())
        val invoice = SantanderPdfFixture.writeOther(context, newFile())

        assertTrue(PdfStatementReader.isSantanderStatement(context, statement))
        assertFalse(PdfStatementReader.isSantanderStatement(context, invoice))

        val invoiceCells = PdfCells.merge(PdfStatementReader.read(context, invoice))
        assertFalse(SantanderStatement.looksLikeSantander(invoiceCells))
        assertTrue(invoiceCells.any { it.text.contains("Invoice 42") }, describe(invoiceCells))
    }

    @Test
    fun aFileThatIsNotAPdfIsATypedError() = runBlocking<Unit> {
        val notPdf = newFile().also { it.writeText("Date,Description,Amount\n01/10/2026,TESCO,-3.00\n") }

        val error = try {
            PdfStatementReader.read(context, notPdf)
            null
        } catch (e: PdfReadException) {
            e
        }

        assertNotNull(error, "reading a CSV as a PDF must fail")
        assertTrue(error.message.orEmpty().isNotBlank())
        assertFalse(PdfStatementReader.isSantanderStatement(context, notPdf))
    }

    @Test
    fun aMissingFileIsATypedError() = runBlocking<Unit> {
        val missing = File(context.cacheDir, "no-such-statement.pdf")

        val error = try {
            PdfStatementReader.read(context, missing)
            null
        } catch (e: PdfReadException) {
            e
        }

        assertNotNull(error)
    }
}
