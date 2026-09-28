package io.github.matteopi.budgeting.testing

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Full-screen screenshots (including dialogs, which Compose's own capture misses) for visual
 * review. They are written to the *test* APK's external files directory, which survives the
 * orchestrator's clearPackageData between tests; CI pulls them from
 * /sdcard/Android/data/io.github.matteopi.budgeting.test/files/screenshots.
 */
object Screenshots {
    fun take(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        FileOutputStream(File(dir, "$safeName.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
