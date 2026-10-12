package io.github.matteopi.budgeting.ui.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.matteopi.budgeting.core.receipts.ReceiptLine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Reads the text of a receipt picture on the device with ML Kit's **bundled** Latin model (it
 * works offline and nothing leaves the phone, docs/PLAN.md §10.1). Results are the fragments
 * ML Kit found with their positions, in image pixels, ready for `ReceiptParsers.parseLines`.
 */
class ReceiptOcr(private val context: Context) {

    private val recognizerHolder = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer: TextRecognizer get() = recognizerHolder.value

    /** Frees the recognizer; call when done with this instance. */
    fun close() {
        if (recognizerHolder.isInitialized()) recognizer.close()
    }

    /** Reads the image at [uri]; `InputImage.fromFilePath` applies the photo's EXIF rotation. */
    suspend fun read(uri: Uri): List<ReceiptLine> {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
        return recognise(image)
    }

    /** Reads [bitmap], which is already upright unless [rotationDegrees] says otherwise. */
    suspend fun read(bitmap: Bitmap, rotationDegrees: Int = 0): List<ReceiptLine> =
        recognise(InputImage.fromBitmap(bitmap, rotationDegrees))

    /**
     * Reads a PDF: every page (at most [MAX_PDF_PAGES]) is drawn at about twice its size on a white
     * background and recognised; the pages are stacked, each one's `top` offset by the height of
     * the pages before it, so the lines read in order.
     */
    suspend fun readPdf(file: File): List<ReceiptLine> {
        val lines = ArrayList<ReceiptLine>()
        var offset = 0f
        val descriptor = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
        try {
            val renderer = withContext(Dispatchers.IO) { PdfRenderer(descriptor) }
            try {
                val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                for (index in 0 until pages) {
                    val bitmap = withContext(Dispatchers.Default) { renderPage(renderer, index) }
                    try {
                        for (line in read(bitmap)) {
                            lines += line.copy(top = line.top + offset, bottom = line.bottom + offset)
                        }
                        offset += bitmap.height + PAGE_GAP
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                renderer.close()
            }
        } finally {
            descriptor.close()
        }
        return lines
    }

    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val width = (page.width * PDF_SCALE).toInt().coerceAtLeast(1)
            val height = (page.height * PDF_SCALE).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PdfRenderer leaves transparent pixels where nothing is drawn; OCR wants white paper.
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    private suspend fun recognise(image: InputImage): List<ReceiptLine> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { text -> continuation.resume(toLines(text)) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

    private fun toLines(text: Text): List<ReceiptLine> =
        text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox
                if (box == null) {
                    ReceiptLine(text = line.text)
                } else {
                    ReceiptLine(
                        text = line.text,
                        top = box.top.toFloat(),
                        left = box.left.toFloat(),
                        right = box.right.toFloat(),
                        bottom = box.bottom.toFloat(),
                    )
                }
            }
        }

    companion object {
        const val MAX_PDF_PAGES = 12
        private const val PDF_SCALE = 2f
        private const val PAGE_GAP = 40f
    }
}
