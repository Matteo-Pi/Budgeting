package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Step 10: hand off to the app (docs/PLAN.md §6.5). */
@Composable
fun DoneStep(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("You're all set", style = MaterialTheme.typography.headlineMedium)
        Text(
            "You can change anything you've entered here later, from Settings or Plan. The " +
                "forecast gets more accurate the more you log.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onFinish) { Text("Start using Budgeting") }
    }
}
