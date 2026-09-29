package io.github.matteopi.budgeting.testing

import androidx.test.core.graphics.writeToTestStorage
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Full-screen screenshots (including dialogs, which Compose's own capture misses) for visual
 * review. Saved through AndroidX Test Storage, which survives the orchestrator's clearPackageData
 * and which AGP pulls into app/build/outputs/connected_android_test_additional_output after the
 * run; CI then publishes them in the device report.
 */
object Screenshots {
    fun take(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        bitmap.writeToTestStorage(name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    }
}
