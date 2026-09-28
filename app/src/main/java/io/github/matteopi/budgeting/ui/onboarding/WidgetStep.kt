package io.github.matteopi.budgeting.ui.onboarding

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.widget.SafeToSpendWidgetReceiver

/** Step 9: pin the home-screen widget where the platform supports it, else give instructions (docs/PLAN.md §6.5). */
@Composable
fun WidgetStep(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val supported = remember(appWidgetManager) { appWidgetManager.isRequestPinAppWidgetSupported }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Add the widget", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The home-screen widget shows what's safe to spend this week and lets you log spending " +
                "in a tap, without opening the app.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (supported) {
            Button(onClick = {
                appWidgetManager.requestPinAppWidget(
                    ComponentName(context, SafeToSpendWidgetReceiver::class.java),
                    null,
                    null,
                )
            }) { Text("Add the widget") }
        } else {
            Text(
                "Long-press an empty spot on your home screen, tap Widgets, then find and add " +
                    "\"Budgeting\".",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
