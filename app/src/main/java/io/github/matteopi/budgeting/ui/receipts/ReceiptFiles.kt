package io.github.matteopi.budgeting.ui.receipts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import io.github.matteopi.budgeting.ui.navigation.Route
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a receipt file in the cache holds, which decides how it is read. Stored in the review route by name. */
enum class ReceiptInputKind {
    IMAGE,
    PDF,
    TEXT,
    ;

    companion object {
        fun fromName(name: String): ReceiptInputKind = entries.firstOrNull { it.name == name } ?: IMAGE
    }
}

/**
 * The cache folder (`cache/receipts`) every receipt input goes through: camera photos, gallery
 * picks and shared content are copied here first, so the review screen only ever deals with a
 * plain file (a shared content URI's permission would not outlive the sharing activity).
 */
object ReceiptFiles {
    /** Sent by quick add to open the app on the scan screen. */
    const val ACTION_SCAN_RECEIPT = "io.github.matteopi.budgeting.action.SCAN_RECEIPT"

    private const val FOLDER = "receipts"
    private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    fun directory(context: Context): File = File(context.cacheDir, FOLDER).apply { mkdirs() }

    /** A new, empty-path file in the receipts folder; old leftovers (over a day) are removed first. */
    fun newFile(context: Context, extension: String): File {
        val dir = directory(context)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        return File(dir, "receipt-${UUID.randomUUID()}.$extension")
    }

    /** True if [file] is inside the receipts folder (the review route is not trusted to point anywhere else). */
    fun isInside(context: Context, file: File): Boolean = try {
        file.canonicalPath.startsWith(directory(context).canonicalPath + File.separator)
    } catch (e: IOException) {
        false
    }

    /** The content URI the camera app writes the photo to. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Copies what [uri] points to into the receipts folder; null if it cannot be read. */
    suspend fun copyToCache(context: Context, uri: Uri, extension: String): File? = withContext(Dispatchers.IO) {
        val file = newFile(context, extension)
        try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                null
            } else {
                input.use { source -> file.outputStream().use { sink -> source.copyTo(sink) } }
                file
            }
        } catch (e: IOException) {
            file.delete()
            null
        } catch (e: SecurityException) {
            file.delete()
            null
        }
    }

    /** Saves shared [text] as a file in the receipts folder. */
    suspend fun writeText(context: Context, text: String): File = withContext(Dispatchers.IO) {
        newFile(context, "txt").also { it.writeText(text) }
    }

    /**
     * Imports what an `ACTION_SEND` intent carries (an image, a PDF, a text file or plain text)
     * and returns the review route for it, or null if there was nothing usable.
     */
    suspend fun importShared(context: Context, intent: Intent): Route.ReceiptReview? {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null) {
            val mime = (context.contentResolver.getType(stream) ?: intent.type).orEmpty()
            val kind = when {
                mime.startsWith("image/") -> ReceiptInputKind.IMAGE
                mime == "application/pdf" -> ReceiptInputKind.PDF
                mime.startsWith("text/") -> ReceiptInputKind.TEXT
                else -> return null
            }
            val extension = when (kind) {
                ReceiptInputKind.IMAGE -> "jpg"
                ReceiptInputKind.PDF -> "pdf"
                ReceiptInputKind.TEXT -> "txt"
            }
            val file = copyToCache(context, stream, extension) ?: return null
            return Route.ReceiptReview(file.absolutePath, kind.name)
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            return Route.ReceiptReview(writeText(context, text).absolutePath, ReceiptInputKind.TEXT.name)
        }
        return null
    }
}
