package io.github.matteopi.budgeting.ui.receipts

import android.content.Context
import android.net.Uri
import io.github.matteopi.budgeting.core.receipts.ReceiptDraft
import io.github.matteopi.budgeting.core.receipts.ReceiptLine
import io.github.matteopi.budgeting.core.receipts.ReceiptParsers
import io.github.matteopi.budgeting.core.receipts.ReceiptSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The picture or text held no text at all (a blurred photo, a blank page). */
class NoTextException(message: String) : Exception(message)

/**
 * Turns a receipt file from the cache folder into a [ReceiptDraft]: OCR for photos and PDFs, the
 * text itself for shared text, then the store parsers of `core`.
 */
class ReceiptReader(private val context: Context) {

    suspend fun read(file: File, kind: ReceiptInputKind): ReceiptDraft {
        if (!ReceiptFiles.isInside(context, file)) throw IllegalArgumentException("Not a receipt file")
        return when (kind) {
            ReceiptInputKind.IMAGE -> ocr { it.read(Uri.fromFile(file)) }.toDraft(ReceiptSource.PHOTO)
            ReceiptInputKind.PDF -> ocr { it.readPdf(file) }.toDraft(ReceiptSource.SHARED_PDF)
            ReceiptInputKind.TEXT -> {
                val text = withContext(Dispatchers.IO) { file.readText() }
                if (text.isBlank()) throw NoTextException(NO_TEXT_SHARED)
                ReceiptParsers.parseText(text, ReceiptSource.SHARED_TEXT)
            }
        }
    }

    private suspend fun ocr(block: suspend (ReceiptOcr) -> List<ReceiptLine>): List<ReceiptLine> {
        val ocr = ReceiptOcr(context)
        try {
            return block(ocr)
        } finally {
            ocr.close()
        }
    }

    private fun List<ReceiptLine>.toDraft(source: ReceiptSource): ReceiptDraft {
        if (none { it.text.isNotBlank() }) throw NoTextException(NO_TEXT_PHOTO)
        return ReceiptParsers.parseLines(this, source)
    }

    companion object {
        const val NO_TEXT_PHOTO = "Couldn't read any text — try a sharper photo"
        const val NO_TEXT_SHARED = "There's no text to read in what was shared"
    }
}
