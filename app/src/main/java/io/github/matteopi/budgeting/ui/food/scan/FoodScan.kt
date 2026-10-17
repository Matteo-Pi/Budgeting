package io.github.matteopi.budgeting.ui.food.scan

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.matteopi.budgeting.core.receipts.LineGrouping
import io.github.matteopi.budgeting.core.recipes.LabelReading
import io.github.matteopi.budgeting.core.recipes.NutritionLabel
import io.github.matteopi.budgeting.core.recipes.OffResult
import io.github.matteopi.budgeting.core.recipes.OpenFoodFacts
import io.github.matteopi.budgeting.ui.receipts.ReceiptOcr
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Reading food packs (docs/PLAN.md §11.5): a nutrition label photo through the same on-device text recognition as
 * receipts, a barcode photo through ML Kit's bundled barcode model (offline), and the opt-in Open Food Facts lookup,
 * the app's only network use, which sends nothing but the barcode number.
 */
object FoodScan {

    private const val TIMEOUT_MS = 10_000

    /** The per 100 g values on the label in the photo at [uri]. */
    suspend fun readLabel(context: Context, uri: Uri): LabelReading {
        val ocr = ReceiptOcr(context.applicationContext)
        try {
            val lines = ocr.read(uri)
            return withContext(Dispatchers.Default) { NutritionLabel.parse(LineGrouping.rows(lines)) }
        } finally {
            ocr.close()
        }
    }

    /** The barcode in the photo at [uri] (EAN-13, EAN-8, UPC-A or UPC-E), normalised for storing; null if none was found. */
    suspend fun readBarcode(context: Context, uri: Uri): String? {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context.applicationContext, uri) }
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build()
        val scanner = BarcodeScanning.getClient(options)
        try {
            return suspendCancellableCoroutine { continuation ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        continuation.resume(barcodes.firstNotNullOfOrNull { it.rawValue?.let(OpenFoodFacts::normaliseBarcode) })
                    }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
        } finally {
            scanner.close()
        }
    }

    /** The app's version name, for the lookup's User-Agent. */
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    /**
     * Looks [barcode] up on Open Food Facts: one GET with the User-Agent they ask for, 10 seconds at most. Only call
     * it when the person has turned *Look up barcodes online* on and has just scanned.
     */
    suspend fun lookUp(context: Context, barcode: String): OffResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(OpenFoodFacts.productUrl(barcode)).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", OpenFoodFacts.userAgent(versionName(context)))
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
                OpenFoodFacts.toResult(status, body, barcode)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            OffResult.Invalid("Couldn't reach Open Food Facts. Check the connection and try again.")
        }
    }
}
