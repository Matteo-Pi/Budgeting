package io.github.matteopi.budgeting.testing

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.time.LocalDate

/**
 * An invented two-page Santander current account statement, drawn into a real PDF with
 * [PdfDocument] so the device tests read it back with PDFBox exactly as they would read a downloaded
 * one. It copies the invented statement of `core`'s `SantanderStatementFixture` (every name, number and
 * reference is made up; `core`'s test sources are not visible from here): page 1 is the account summary,
 * page 2 an "Average balance" table (a decoy that also starts with a date) and then the transactions
 * table, with wrapped rows, card rows paid through a wallet, bill payments, and moves between the
 * holder's own accounts.
 *
 * Positions are those of the core fixture (A4, 7pt text, y is the baseline). Right-aligned cells are
 * placed from their right edge using the real width of the text, as the bank's PDF does. The text is set in
 * Liberation Sans (an Arial look-alike that PDFBox ships in its assets, so it is in the app too): it is a
 * static TrueType font, which `PdfDocument` embeds with a text map, unlike the variable system fonts of
 * recent Android versions that it can only draw as shapes.
 */
object SantanderPdfFixture {

    const val ACCOUNT_HOLDER = "MISS ALEX TESTER"
    const val OPENING_PENCE = 0L
    const val CLOSING_PENCE = 1_479L
    const val TOTAL_IN_PENCE = 50_000L
    const val TOTAL_OUT_PENCE = 48_521L
    val PERIOD_START: LocalDate = LocalDate.of(2026, 9, 10)
    val PERIOD_END: LocalDate = LocalDate.of(2026, 10, 10)

    private const val FONT_SIZE = 7f
    private const val DATE_X = 54.1f
    private const val DESCRIPTION_X = 116.0f
    private const val MONEY_IN_RIGHT = 455.6f
    private const val MONEY_OUT_RIGHT = 502.0f
    private const val BALANCE_RIGHT = 548.6f

    /** One transaction row, as printed and as it should be read. */
    class Row(
        private val printedDate: String,
        val descriptionLines: List<String>,
        val moneyIn: String?,
        val moneyOut: String?,
        val balance: String,
        /** The description after cleaning. */
        val cleaned: String,
        val postedDate: LocalDate,
        /** The date the row gets: the card date when the description has one. */
        val date: LocalDate,
        val likelyTransfer: Boolean,
    ) {
        val pence: Long get() = moneyIn?.let { toPence(it) } ?: -toPence(requireNotNull(moneyOut))
        val balancePence: Long get() = toPence(balance)
        val dateText: String get() = printedDate
    }

    private fun row(
        printedDate: String,
        description: String,
        moneyIn: String?,
        moneyOut: String?,
        balance: String,
        cleaned: String,
        posted: LocalDate,
        card: LocalDate? = null,
        transfer: Boolean = false,
        wrappedAfter: String? = null,
    ) = Row(
        printedDate = printedDate,
        descriptionLines = listOfNotNull(description, wrappedAfter),
        moneyIn = moneyIn,
        moneyOut = moneyOut,
        balance = balance,
        cleaned = cleaned,
        postedDate = posted,
        date = card ?: posted,
        likelyTransfer = transfer,
    )

    private fun d(month: Int, day: Int) = LocalDate.of(2026, month, day)

    val rows: List<Row> = listOf(
        row("24th Sep", "FASTER PAYMENTS RECEIPT REF.11223344556677 FROM A Tester", "500.00", null, "500.00", "From A Tester", d(9, 24), transfer = true),
        row("27th Sep", "TRANSFER TO ALEX TESTER", null, "200.00", "300.00", "TRANSFER TO ALEX TESTER", d(9, 27), transfer = true),
        row(
            "29th Sep", "REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678, MANDATE NO 1", null, "200.00", "100.00",
            "REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678", d(9, 29), transfer = true,
        ),
        row(
            "2nd Oct", "BILL PAYMENT VIA FASTER PAYMENT TO JO BLOGGS REFERENCE pizza , MANDATE NO 0002", null, "2.17", "97.83",
            "JO BLOGGS, ref pizza", d(10, 2),
        ),
        row(
            "4th Oct", "BILL PAYMENT VIA FASTER PAYMENT TO ALEX TESTER REFERENCE Transfer", null, "30.00", "67.83",
            "ALEX TESTER, ref Transfer", d(10, 4), transfer = true, wrappedAfter = ", MANDATE NO 0003",
        ),
        row("6th Oct", "BOULDER WORLD (VIA GOOGLE PAY), ON 05-10-2026", null, "8.00", "59.83", "BOULDER WORLD", d(10, 6), card = d(10, 5)),
        row("6th Oct", "UNION SHOP (VIA GOOGLE PAY), ON 05-10-2026", null, "0.45", "59.38", "UNION SHOP", d(10, 6), card = d(10, 5)),
        row("7th Oct", "MORRISONS LEEDS - (VIA GOOGLE PAY), ON 06-10-2026", null, "1.00", "58.38", "MORRISONS LEEDS", d(10, 7), card = d(10, 6)),
        row("7th Oct", "UNION SHOP (VIA GOOGLE PAY), ON 06-10-2026", null, "17.31", "41.07", "UNION SHOP", d(10, 7), card = d(10, 6)),
        row("7th Oct", "SAINSBURYS S/MKTS (VIA GOOGLE PAY), ON 06-10-2026", null, "0.25", "40.82", "SAINSBURYS S/MKTS", d(10, 7), card = d(10, 6)),
        row("8th Oct", "SQ *CORNER CAFE (VIA GOOGLE PAY), ON 07-10-2026", null, "2.20", "38.62", "SQ *CORNER CAFE", d(10, 8), card = d(10, 7)),
        row("9th Oct", "MORRISONS LEEDS - (VIA GOOGLE PAY), ON 08-10-2026", null, "2.49", "36.13", "MORRISONS LEEDS", d(10, 9), card = d(10, 8)),
        row("9th Oct", "TESCO STORES 1234 (VIA GOOGLE PAY), ON 08-10-2026", null, "1.83", "34.30", "TESCO STORES 1234", d(10, 9), card = d(10, 8)),
        row("9th Oct", "TESCO STORES 5678 (VIA GOOGLE PAY), ON 08-10-2026", null, "7.75", "26.55", "TESCO STORES 5678", d(10, 9), card = d(10, 8)),
        row("9th Oct", "UNION SHOP (VIA GOOGLE PAY), ON 08-10-2026", null, "0.70", "25.85", "UNION SHOP", d(10, 9), card = d(10, 8)),
        row("9th Oct", "UNION SHOP (VIA GOOGLE PAY), ON 08-10-2026", null, "0.89", "24.96", "UNION SHOP", d(10, 9), card = d(10, 8)),
        row("10th Oct", "UNION SHOP (VIA GOOGLE PAY), ON 09-10-2026", null, "0.77", "24.19", "UNION SHOP", d(10, 10), card = d(10, 9)),
        row("10th Oct", "NYX*PHOTOBOOTH (VIA GOOGLE PAY), ON 09-10-2026", null, "5.00", "19.19", "NYX*PHOTOBOOTH", d(10, 10), card = d(10, 9)),
        row(
            "10th Oct", "BILL PAYMENT VIA FASTER PAYMENT TO SAM SMITH REFERENCE tickets , MANDATE NO 0004", null, "4.40", "14.79",
            "SAM SMITH, ref tickets", d(10, 10),
        ),
    )

    private fun toPence(text: String): Long = text.replace(",", "").replace(".", "").toLong()

    /** One piece of text to draw: [x] is the left edge, or the right edge when [rightAligned]. */
    private class Draw(val text: String, val x: Float, val y: Float, val page: Int, val rightAligned: Boolean = false)

    private fun drawings(): List<Draw> {
        val out = mutableListOf(
            // Page 1: the account summary.
            Draw("Call Telephone Banking for questions or", 393.1f, 150.0f, 1),
            Draw("available at www.santander.co.uk", 393.1f, 221.0f, 1),
            Draw("Santander Banking Operations", 393.1f, 241.4f, 1),
            Draw("Your account summary for", 54.9f, 328.8f, 1),
            Draw("10th Sep 2026 to 10th Oct 2026", 54.9f, 344.9f, 1),
            Draw("Account name", 54.9f, 363.8f, 1),
            Draw(ACCOUNT_HOLDER, 110.0f, 363.8f, 1),
            Draw("Account number: 12345678 Sort Code: 00 00 00 Statement number: 01/2026", 54.9f, 375.7f, 1),
            Draw("BIC: ABBYGB2LXXX IBAN: GB00 ABBY 0000 0012 3456 78", 54.9f, 387.6f, 1),
            Draw("Balance brought forward from previous Statement", 54.9f, 399.5f, 1),
            Draw("£0.00", 359.7f, 399.5f, 1, rightAligned = true),
            Draw("Total money in:", 54.9f, 411.4f, 1),
            Draw("£500.00", 359.7f, 411.4f, 1, rightAligned = true),
            Draw("Total money out:", 54.9f, 423.3f, 1),
            Draw("-£485.21", 359.7f, 423.3f, 1, rightAligned = true),
            Draw("Your balance at close of business 10th Oct 2026", 54.9f, 435.6f, 1),
            Draw("£14.79", 359.7f, 435.6f, 1, rightAligned = true),
            Draw("Your overdraft limit is £1,500.00. Arranged overdraft interest rate is 0.00% EAR", 54.9f, 454.1f, 1),
            Draw("Continued on reverse....", 300.0f, 800.0f, 1),
            Draw("Page number: 1 of 2", 480.0f, 812.0f, 1),
            // Page 2: the page header and the "Average balance" decoy table.
            Draw("Account name: $ACCOUNT_HOLDER", 300.0f, 40.0f, 2),
            Draw("Statement number: 01/2026", 300.0f, 60.0f, 2),
            Draw("Page number: 2 of 2", 420.0f, 60.0f, 2),
            Draw("Important messages", 250.0f, 100.0f, 2),
            Draw("Date", DATE_X, 407.2f, 2),
            Draw("Average balance for the month", DESCRIPTION_X, 407.2f, 2),
            Draw("Amount", 520.0f, 407.2f, 2),
            Draw("10th Oct", DATE_X, 416.5f, 2),
            Draw("Average credit balance", DESCRIPTION_X, 416.5f, 2),
            Draw("£101.28", BALANCE_RIGHT, 416.5f, 2, rightAligned = true),
            Draw("Your transactions 10th Sep 2026 to 10th Oct 2026", DATE_X, 437.0f, 2),
        )

        val headerY = 446.4f
        out += Draw("Date", DATE_X, headerY, 2)
        out += Draw("Description", DESCRIPTION_X, headerY, 2)
        out += Draw("Money in", 427.3f, headerY, 2)
        out += Draw("Money out", 469.7f, headerY, 2)
        out += Draw("£ Balance", 517.9f, headerY, 2)

        var y = headerY + 9.5f
        out += Draw("10th Sep", DATE_X, y, 2)
        out += Draw("Opening Balance", DESCRIPTION_X, y, 2)
        out += Draw("0.00", BALANCE_RIGHT, y, 2, rightAligned = true)

        for (row in rows) {
            val wrapped = row.descriptionLines.size > 1
            y += if (wrapped) 13.6f else 9.5f
            out += Draw(row.dateText, DATE_X, y, 2)
            if (wrapped) {
                out += Draw(row.descriptionLines[0], DESCRIPTION_X, y - 4.0f, 2)
                out += Draw(row.descriptionLines[1], DESCRIPTION_X, y + 4.0f, 2)
            } else {
                out += Draw(row.descriptionLines[0], DESCRIPTION_X, y, 2)
            }
            row.moneyIn?.let { out += Draw(it, MONEY_IN_RIGHT, y, 2, rightAligned = true) }
            row.moneyOut?.let { out += Draw(it, MONEY_OUT_RIGHT, y, 2, rightAligned = true) }
            out += Draw(row.balance, BALANCE_RIGHT, y, 2, rightAligned = true)
            if (wrapped) y += 4.0f
        }
        y += 9.6f
        out += Draw("10th Oct", DATE_X, y, 2)
        out += Draw("Balance carried forward to next statement:", DESCRIPTION_X, y, 2)
        out += Draw("14.79", BALANCE_RIGHT, y, 2, rightAligned = true)
        return out
    }

    /** Draws the statement into a new PDF at [file] (A4, two pages) and returns it. */
    fun write(context: Context, file: File): File = writePdf(context, file, pages = 2, drawings = drawings())

    /** A one-page PDF with text that is not a Santander statement (an invented invoice). */
    fun writeOther(context: Context, file: File): File = writePdf(
        context,
        file,
        pages = 1,
        drawings = listOf(
            Draw("Invoice 42", DATE_X, 100f, 1),
            Draw("Pizza Palace Ltd", DATE_X, 120f, 1),
            Draw("Total due", DATE_X, 140f, 1),
            Draw("£12.50", BALANCE_RIGHT, 140f, 1, rightAligned = true),
        ),
    )

    private fun statementTypeface(context: Context): Typeface =
        runCatching { Typeface.createFromAsset(context.assets, "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf") }
            .getOrDefault(Typeface.SANS_SERIF)

    private fun writePdf(context: Context, file: File, pages: Int, drawings: List<Draw>): File {
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = FONT_SIZE
            typeface = statementTypeface(context)
            isAntiAlias = true
        }
        val document = PdfDocument()
        try {
            for (pageNumber in 1..pages) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                for (draw in drawings.filter { it.page == pageNumber }) {
                    val x = if (draw.rightAligned) draw.x - paint.measureText(draw.text) else draw.x
                    page.canvas.drawText(draw.text, x, draw.y, paint)
                }
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return file
    }
}
