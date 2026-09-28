package io.github.matteopi.budgeting.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/**
 * The "safe to spend" home-screen widget. Real responsive layouts (small/wide/tall), the "+"
 * quick-add action and preset buttons are WP-U1; this is a minimal stub so the receiver, manifest
 * entry and [WidgetUpdater] have something real to call.
 */
class SafeToSpendWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Text("Budgeting")
        }
    }
}
