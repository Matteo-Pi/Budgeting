package io.github.matteopi.budgeting.ui.importer

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.matteopi.budgeting.core.importer.PdfCells
import io.github.matteopi.budgeting.core.importer.PdfTextChunk
import io.github.matteopi.budgeting.core.importer.SantanderStatement
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A PDF that could not be read at all (damaged, password protected, not a PDF). [message] is for the user. */
class PdfReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads the text layer of a PDF with its exact positions, using PDFBox (docs/PLAN.md §5.16). The
 * chunks come out in drawing order, one per glyph; `core`'s `PdfCells.merge` joins them into cells.
 */
object PdfStatementReader {
    private val initLock = Any()
    private var initialised = false

    /** Every glyph of the PDF at [uri]. Throws [PdfReadException] if it cannot be read. */
    suspend fun read(context: Context, uri: Uri): List<PdfTextChunk> = readFrom(context) {
        context.contentResolver.openInputStream(uri) ?: throw PdfReadException("Couldn't open that file.")
    }

    /** Every glyph of the PDF in [file]. Throws [PdfReadException] if it cannot be read. */
    suspend fun read(context: Context, file: File): List<PdfTextChunk> = readFrom(context) { file.inputStream() }

    /** True if [file] is a PDF that looks like a Santander statement; false for anything else, readable or not. */
    suspend fun isSantanderStatement(context: Context, file: File): Boolean = try {
        SantanderStatement.looksLikeSantander(PdfCells.merge(read(context, file)))
    } catch (e: PdfReadException) {
        false
    }

    private suspend fun readFrom(context: Context, open: () -> InputStream): List<PdfTextChunk> =
        withContext(Dispatchers.IO) {
            try {
                initialise(context)
                open().use { input ->
                    PDDocument.load(input).use { document ->
                        val collector = GlyphCollector()
                        collector.getText(document)
                        collector.chunks
                    }
                }
            } catch (e: PdfReadException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw PdfReadException("Couldn't read that PDF. It may be damaged or protected by a password.", e)
            } catch (e: RuntimeException) {
                throw PdfReadException("Couldn't read that PDF.", e)
            } catch (e: OutOfMemoryError) {
                throw PdfReadException("That PDF is too big to read on this phone.", e)
            }
        }

    /** PDFBox needs its resource loader (fonts, glyph lists) pointed at the app's assets once. */
    private fun initialise(context: Context) {
        synchronized(initLock) {
            if (!initialised) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialised = true
            }
        }
    }

    /**
     * Collects each glyph PDFBox positions instead of letting it build lines of text. Drawing order is
     * kept ([sortByPosition] stays off) because the table rows are read in that order. Text drawn twice in
     * the same place (a way of faking bold) is dropped, as PDFBox's own text extraction does.
     */
    private class GlyphCollector : PDFTextStripper() {
        val chunks = ArrayList<PdfTextChunk>()

        private val drawn = HashMap<String, MutableList<PdfTextChunk>>()
        private var drawnOnPage = 0

        init {
            sortByPosition = false
        }

        override fun processTextPosition(text: TextPosition) {
            val unicode = text.unicode
            if (unicode.isNullOrEmpty()) return
            val x = text.xDirAdj
            val width = text.widthDirAdj
            val y = text.yDirAdj
            val page = currentPageNo
            val chunk = PdfTextChunk(
                text = unicode,
                x = x,
                y = y,
                endX = x + width,
                height = text.fontSizeInPt.takeIf { it > 0f } ?: text.heightDir,
                page = page,
            )

            if (page != drawnOnPage) {
                drawn.clear()
                drawnOnPage = page
            }
            if (width > 0f) {
                val tolerance = width / unicode.length / 3f
                val sameGlyphs = drawn.getOrPut(unicode) { ArrayList() }
                if (sameGlyphs.any { abs(it.x - x) < tolerance && abs(it.y - y) < tolerance }) return
                sameGlyphs += chunk
            }
            chunks += chunk
        }
    }
}
