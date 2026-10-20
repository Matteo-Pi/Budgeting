package io.github.matteopi.budgeting.core.importer

/**
 * A made-up two-page Santander current account statement, laid out exactly like the real PDF
 * statements Santander's online banking produces (same cells, drawing order, columns and row
 * heights; every name, number and reference here is invented). The real layout:
 *
 * - Page 1 has the account summary: the period, account name, "Balance brought forward from
 *   previous Statement", "Total money in:", "Total money out:" (drawn as "-£485.21") and
 *   "Your balance at close of business <date>", each label and value a separate cell.
 * - Page 2 has an "Average balance" table first (its row also starts with a date cell, so it must
 *   not be taken for a transaction), then "Your transactions <start> to <end>", the column header
 *   cells "Date", "Description", "Money in", "Money out", "£ Balance", then one row per
 *   transaction. Each cell is drawn separately and rows are drawn top to bottom, cell by cell:
 *   date, description (one cell per line when it wraps), the amount (right-aligned in the
 *   "Money in" or "Money out" column), the balance (right-aligned). A wrapped row's date and
 *   amounts sit on the middle line, between its two description lines.
 * - The table starts with "Opening Balance" (balance only) and ends with "Balance carried forward
 *   to next statement:" (balance only).
 *
 * Text is 7pt Arial; [CHAR_WIDTH] is a fixed stand-in for a glyph's width.
 */
object SantanderStatementFixture {

    const val FONT_SIZE = 7f
    const val CHAR_WIDTH = 3.5f

    private const val DATE_X = 54.1f
    private const val DESCRIPTION_X = 116.0f
    private const val MONEY_IN_RIGHT = 455.6f
    private const val MONEY_OUT_RIGHT = 502.0f
    private const val BALANCE_RIGHT = 548.6f

    /** A whole cell drawn at [x] (left edge), with y growing down the page. */
    private fun cell(text: String, x: Float, y: Float, page: Int) =
        PdfTextChunk(text, x, y, x + text.length * CHAR_WIDTH, FONT_SIZE, page)

    private fun rightAligned(text: String, right: Float, y: Float, page: Int) =
        cell(text, right - text.length * CHAR_WIDTH, y, page)

    private class Row(
        val date: String,
        val descriptionLines: List<String>,
        val moneyIn: String?,
        val moneyOut: String?,
        val balance: String,
    )

    private val rows = listOf(
        Row("24th Sep", listOf("FASTER PAYMENTS RECEIPT REF.11223344556677 FROM A Tester"), "500.00", null, "500.00"),
        Row("27th Sep", listOf("TRANSFER TO ALEX TESTER"), null, "200.00", "300.00"),
        Row("29th Sep", listOf("REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678, MANDATE NO 1"), null, "200.00", "100.00"),
        Row("2nd Oct", listOf("BILL PAYMENT VIA FASTER PAYMENT TO JO BLOGGS REFERENCE pizza , MANDATE NO 0002"), null, "2.17", "97.83"),
        Row(
            "4th Oct",
            listOf("BILL PAYMENT VIA FASTER PAYMENT TO ALEX TESTER REFERENCE Transfer", ", MANDATE NO 0003"),
            null, "30.00", "67.83",
        ),
        Row("6th Oct", listOf("BOULDER WORLD (VIA GOOGLE PAY), ON 05-10-2026"), null, "8.00", "59.83"),
        Row("6th Oct", listOf("UNION SHOP (VIA GOOGLE PAY), ON 05-10-2026"), null, "0.45", "59.38"),
        Row("7th Oct", listOf("MORRISONS LEEDS - (VIA GOOGLE PAY), ON 06-10-2026"), null, "1.00", "58.38"),
        Row("7th Oct", listOf("UNION SHOP (VIA GOOGLE PAY), ON 06-10-2026"), null, "17.31", "41.07"),
        Row("7th Oct", listOf("SAINSBURYS S/MKTS (VIA GOOGLE PAY), ON 06-10-2026"), null, "0.25", "40.82"),
        Row("8th Oct", listOf("SQ *CORNER CAFE (VIA GOOGLE PAY), ON 07-10-2026"), null, "2.20", "38.62"),
        Row("9th Oct", listOf("MORRISONS LEEDS - (VIA GOOGLE PAY), ON 08-10-2026"), null, "2.49", "36.13"),
        Row("9th Oct", listOf("TESCO STORES 1234 (VIA GOOGLE PAY), ON 08-10-2026"), null, "1.83", "34.30"),
        Row("9th Oct", listOf("TESCO STORES 5678 (VIA GOOGLE PAY), ON 08-10-2026"), null, "7.75", "26.55"),
        Row("9th Oct", listOf("UNION SHOP (VIA GOOGLE PAY), ON 08-10-2026"), null, "0.70", "25.85"),
        Row("9th Oct", listOf("UNION SHOP (VIA GOOGLE PAY), ON 08-10-2026"), null, "0.89", "24.96"),
        Row("10th Oct", listOf("UNION SHOP (VIA GOOGLE PAY), ON 09-10-2026"), null, "0.77", "24.19"),
        Row("10th Oct", listOf("NYX*PHOTOBOOTH (VIA GOOGLE PAY), ON 09-10-2026"), null, "5.00", "19.19"),
        Row("10th Oct", listOf("BILL PAYMENT VIA FASTER PAYMENT TO SAM SMITH REFERENCE tickets , MANDATE NO 0004"), null, "4.40", "14.79"),
    )

    /** Every transaction row, top to bottom: the raw description (wrapped lines joined by a space), signed pence, balance pence. */
    val expectedRows: List<Triple<String, Long, Long>> = rows.map { row ->
        val pence = row.moneyIn?.let { toPence(it) } ?: -toPence(row.moneyOut!!)
        Triple(row.descriptionLines.joinToString(" "), pence, toPence(row.balance))
    }

    const val ACCOUNT_HOLDER = "MISS ALEX TESTER"
    const val OPENING_PENCE = 0L
    const val CLOSING_PENCE = 1_479L
    const val TOTAL_IN_PENCE = 50_000L
    const val TOTAL_OUT_PENCE = 48_521L

    private fun toPence(text: String): Long = text.replace(",", "").replace(".", "").toLong()

    private fun page1(): List<PdfTextChunk> = listOf(
        cell("Call Telephone Banking for questions or", 393.1f, 150.0f, 1),
        cell("available at www.santander.co.uk", 393.1f, 221.0f, 1),
        cell("Santander Banking Operations", 393.1f, 241.4f, 1),
        cell("Your account summary for", 54.9f, 328.8f, 1),
        cell("10th Sep 2026 to 10th Oct 2026", 54.9f, 344.9f, 1),
        cell("Account name ", 54.9f, 363.8f, 1),
        cell(ACCOUNT_HOLDER, 110.0f, 363.8f, 1),
        cell("Account number: 12345678 Sort Code: 00 00 00 Statement number: 01/2026", 54.9f, 375.7f, 1),
        cell("BIC: ABBYGB2LXXX IBAN: GB00 ABBY 0000 0012 3456 78", 54.9f, 387.6f, 1),
        cell("Balance brought forward from previous Statement", 54.9f, 399.5f, 1),
        rightAligned("£0.00", 359.7f, 399.5f, 1),
        cell("Total money in:", 54.9f, 411.4f, 1),
        rightAligned("£500.00", 359.7f, 411.4f, 1),
        cell("Total money out:", 54.9f, 423.3f, 1),
        rightAligned("-£485.21", 359.7f, 423.3f, 1),
        cell("Your balance at close of business 10th Oct 2026", 54.9f, 435.6f, 1),
        rightAligned("£14.79", 359.7f, 435.6f, 1),
        cell("Your overdraft limit is £1,200.00. Arranged overdraft interest rate is 0.00% EAR", 54.9f, 454.1f, 1),
        cell("Continued on reverse....", 300.0f, 800.0f, 1),
        cell("Page number: 1 of 2", 480.0f, 812.0f, 1),
    )

    private fun page2(): List<PdfTextChunk> {
        val out = mutableListOf(
            cell("Account name: $ACCOUNT_HOLDER", 300.0f, 40.0f, 2),
            cell("Statement number: 01/2026", 300.0f, 60.0f, 2),
            cell("Page number: 2 of 2", 420.0f, 60.0f, 2),
            cell("Important messages", 250.0f, 100.0f, 2),
            cell("Date", DATE_X, 407.2f, 2),
            cell("Average balance for the month", DESCRIPTION_X, 407.2f, 2),
            cell("Amount", 520.0f, 407.2f, 2),
            cell("10th Oct", DATE_X, 416.5f, 2),
            cell("Average credit balance", DESCRIPTION_X, 416.5f, 2),
            rightAligned("£101.28", BALANCE_RIGHT, 416.5f, 2),
            cell("Your transactions 10th Sep 2026 to 10th Oct 2026", DATE_X, 437.0f, 2),
        )
        val headerY = 446.4f
        out += cell("Date", DATE_X, headerY, 2)
        out += cell("Description", DESCRIPTION_X, headerY, 2)
        out += cell("Money in", 427.3f, headerY, 2)
        out += cell("Money out", 469.7f, headerY, 2)
        out += cell("£ Balance", 517.9f, headerY, 2)

        var y = headerY + 9.5f
        out += cell("10th Sep", DATE_X, y, 2)
        out += cell("Opening Balance", DESCRIPTION_X, y, 2)
        out += rightAligned("0.00", BALANCE_RIGHT, y, 2)

        for (row in rows) {
            val wrapped = row.descriptionLines.size > 1
            y += if (wrapped) 13.6f else 9.5f
            out += cell(row.date, DATE_X, y, 2)
            if (wrapped) {
                out += cell(row.descriptionLines[0], DESCRIPTION_X, y - 4.0f, 2)
                out += cell(row.descriptionLines[1], DESCRIPTION_X, y + 4.0f, 2)
            } else {
                out += cell(row.descriptionLines[0], DESCRIPTION_X, y, 2)
            }
            row.moneyIn?.let { out += rightAligned(it, MONEY_IN_RIGHT, y, 2) }
            row.moneyOut?.let { out += rightAligned(it, MONEY_OUT_RIGHT, y, 2) }
            out += rightAligned(row.balance, BALANCE_RIGHT, y, 2)
            if (wrapped) y += 4.0f
        }
        y += 9.6f
        out += cell("10th Oct", DATE_X, y, 2)
        out += cell("Balance carried forward to next statement:", DESCRIPTION_X, y, 2)
        out += rightAligned("14.79", BALANCE_RIGHT, y, 2)
        return out
    }

    /** The statement as whole cells, in drawing order (what a cell-level extractor reports). */
    fun cells(): List<PdfTextChunk> = page1() + page2()

    /** The same statement one glyph at a time, spaces included (what PDFBox's per-glyph callback reports). */
    fun glyphs(): List<PdfTextChunk> = cells().flatMap { c ->
        c.text.mapIndexed { i, ch ->
            val x = c.x + i * CHAR_WIDTH
            PdfTextChunk(ch.toString(), x, c.y, x + CHAR_WIDTH, c.height, c.page)
        }
    }

    /** The same statement one word at a time with no space chunks (the gap between words is one space wide). */
    fun words(): List<PdfTextChunk> = cells().flatMap { c ->
        val out = mutableListOf<PdfTextChunk>()
        var i = 0
        while (i < c.text.length) {
            if (c.text[i] == ' ') { i++; continue }
            val end = c.text.indexOf(' ', i).let { if (it < 0) c.text.length else it }
            val x = c.x + i * CHAR_WIDTH
            out += PdfTextChunk(c.text.substring(i, end), x, c.y, x + (end - i) * CHAR_WIDTH, c.height, c.page)
            i = end
        }
        out
    }
}
