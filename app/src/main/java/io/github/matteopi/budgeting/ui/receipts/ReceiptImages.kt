package io.github.matteopi.budgeting.ui.receipts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.IOException

/** Decodes receipt photos for display (the thumbnail and the full-size viewer). */
object ReceiptImages {

    /**
     * The image in [file], upright (EXIF rotation applied) and no more than about [maxSide]
     * pixels on its longer side (decoded at a power-of-two reduction, then possibly a little larger).
     * Null if the file is not a readable image. Slow: call off the main thread.
     */
    fun load(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        val degrees = rotationDegrees(file)
        if (degrees == 0) return decoded
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private fun rotationDegrees(file: File): Int = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }
}
