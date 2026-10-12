package io.github.matteopi.budgeting.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.receipts.ReceiptDraft
import io.github.matteopi.budgeting.core.receipts.ReceiptParsers
import io.github.matteopi.budgeting.core.receipts.ReceiptSource
import io.github.matteopi.budgeting.core.receipts.Store
import io.github.matteopi.budgeting.testing.ReceiptTestData
import io.github.matteopi.budgeting.ui.receipts.ReceiptFiles
import io.github.matteopi.budgeting.ui.receipts.ReceiptInputKind
import io.github.matteopi.budgeting.ui.receipts.ReceiptOcr
import io.github.matteopi.budgeting.ui.receipts.ReceiptReader
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ML Kit's bundled text recognition on a synthetic Tesco receipt (black monospace text on white),
 * through the same path real receipts take: OCR fragments, then `ReceiptParsers`. OCR may misread
 * a character now and then, so most item prices must match, not all.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptOcrTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val date: LocalDate = LocalDate.now().minusDays(1)

    private fun <T> blockingLong(block: suspend () -> T): T = runBlocking { withTimeout(180_000) { block() } }

    private fun assertReadWell(draft: ReceiptDraft) {
        assertEquals(Store.TESCO, draft.store, "store (items: ${draft.items.map { it.rawName }})")
        assertEquals(ReceiptTestData.total, draft.total, "total")
        val expected = ReceiptTestData.items.map { Money(it.pence) }
        val found = expected.count { price -> draft.items.any { it.price == price } }
        assertTrue(
            found * 10 >= expected.size * 8,
            "only $found of ${expected.size} item prices were read; items: " +
                draft.items.joinToString { "${it.rawName} ${it.price}" },
        )
    }

    @Test
    fun bitmapReceiptIsReadAndParsed() {
        val bitmap = ReceiptTestData.bitmap(date)
        val lines = blockingLong {
            val ocr = ReceiptOcr(context)
            try {
                ocr.read(bitmap)
            } finally {
                ocr.close()
            }
        }
        assertTrue(lines.isNotEmpty(), "no text recognised")
        assertTrue(lines.any { it.bottom > it.top }, "recognised lines should carry their positions")
        assertReadWell(ReceiptParsers.parseLines(lines, ReceiptSource.PHOTO))
    }

    @Test
    fun imageFileIsReadThroughTheReceiptReader() {
        val file = writeJpeg(ReceiptTestData.bitmap(date))
        try {
            val draft = blockingLong { ReceiptReader(context).read(file, ReceiptInputKind.IMAGE) }
            assertEquals(ReceiptSource.PHOTO, draft.source)
            assertReadWell(draft)
        } finally {
            file.delete()
        }
    }

    @Test
    fun pdfReceiptIsRenderedAndRead() {
        val file = ReceiptFiles.newFile(context, "pdf")
        try {
            ReceiptTestData.writePdf(date, file)
            val draft = blockingLong { ReceiptReader(context).read(file, ReceiptInputKind.PDF) }
            assertEquals(ReceiptSource.SHARED_PDF, draft.source)
            assertReadWell(draft)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aBlankPictureHasNoText() {
        val blank = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val file = writeJpeg(blank)
        try {
            val failure = runCatching { blockingLong { ReceiptReader(context).read(file, ReceiptInputKind.IMAGE) } }
                .exceptionOrNull()
            assertTrue(failure is io.github.matteopi.budgeting.ui.receipts.NoTextException, "expected NoTextException, got $failure")
            assertEquals(ReceiptReader.NO_TEXT_PHOTO, failure?.message)
        } finally {
            file.delete()
        }
    }

    private fun writeJpeg(bitmap: Bitmap): File {
        val file = ReceiptFiles.newFile(context, "jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file
    }
}
