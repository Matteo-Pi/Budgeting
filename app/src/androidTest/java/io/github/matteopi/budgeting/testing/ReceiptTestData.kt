package io.github.matteopi.budgeting.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import io.github.matteopi.budgeting.core.model.Money
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A synthetic Tesco till receipt for the receipt device tests: as plain text (what a shared receipt
 * looks like), drawn on a bitmap (for OCR) and drawn into a PDF. Ten items adding up to £16.64.
 */
object ReceiptTestData {

    class Item(val name: String, val pence: Long)

    val items = listOf(
        Item("HOVIS MEDIUM SLICED 800G", 130),
        Item("SEMI SKIMMED MILK 2PT", 145),
        Item("BANANAS LOOSE 5 PACK", 99),
        Item("CHEDDAR MATURE 350G", 220),
        Item("CHICKEN BREAST 650G", 420),
        Item("BAKED BEANS 400G", 65),
        Item("TOMATOES CHERRY 250G", 110),
        Item("FUSILLI PASTA 500G", 85),
        Item("GREEK YOGHURT 500G", 135),
        Item("SATSUMAS EASY PEEL", 255),
    )

    val totalPence: Long = items.sumOf { it.pence }

    val total: Money = Money(totalPence)

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** One printed row: [left] text and, when set, a right-aligned amount. */
    class Row(val left: String, val right: String? = null)

    fun rows(date: LocalDate): List<Row> = buildList {
        add(Row("TESCO EXTRA"))
        add(Row("Tesco Stores Ltd"))
        add(Row(date.format(DATE_TIME) + " 14:32"))
        items.forEach { add(Row(it.name, amount(it.pence))) }
        add(Row("TOTAL TO PAY", "£" + amount(totalPence)))
        add(Row("VISA CONTACTLESS", "£" + amount(totalPence)))
        add(Row("Thank you for shopping at Tesco"))
    }

    /** The receipt as shared text: each row on one line, the amount after some spaces. */
    fun text(date: LocalDate): String =
        rows(date).joinToString("\n") { row -> if (row.right == null) row.left else "${row.left}   ${row.right}" }

    /** "1.30" for 130. */
    fun amount(pence: Long): String = "${pence / 100}.${(pence % 100).toString().padStart(2, '0')}"

    /**
     * The receipt as black monospace text on a white bitmap, names on the left and amounts flush
     * right, like a till roll photographed square on.
     */
    fun bitmap(date: LocalDate): Bitmap {
        val width = 1000
        val lineHeight = 72
        val rows = rows(date)
        val bitmap = Bitmap.createBitmap(width, lineHeight * (rows.size + 2), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val left = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.MONOSPACE
            textSize = 42f
        }
        val right = Paint(left).apply { textAlign = Paint.Align.RIGHT }
        rows.forEachIndexed { index, row ->
            val baseline = lineHeight * (index + 1.5f)
            canvas.drawText(row.left, 40f, baseline, left)
            if (row.right != null) canvas.drawText(row.right, width - 40f, baseline, right)
        }
        return bitmap
    }

    /** The receipt as a one-page A4 PDF (text drawn as vector text, as an emailed receipt would be). */
    fun writePdf(date: LocalDate, file: File) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val left = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.MONOSPACE
                textSize = 18f
            }
            val right = Paint(left).apply { textAlign = Paint.Align.RIGHT }
            rows(date).forEachIndexed { index, row ->
                val baseline = 60f + 34f * index
                page.canvas.drawText(row.left, 40f, baseline, left)
                if (row.right != null) page.canvas.drawText(row.right, 555f, baseline, right)
            }
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
