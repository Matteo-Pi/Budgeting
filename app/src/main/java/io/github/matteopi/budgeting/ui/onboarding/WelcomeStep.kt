package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Step 1: what the app does, in a couple of lines, and the privacy promise (docs/PLAN.md §6.5). */
@Composable
fun WelcomeStep(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Welcome to Budgeting", style = MaterialTheme.typography.headlineMedium)
        Text(
            modifier = Modifier.padding(top = 16.dp),
            text = "Budgeting tracks your spending and forecasts your cash flow, so you always know " +
                "what's safe to spend this week. Add your accounts, term dates and regular bills, and " +
                "it learns the rest from what you log.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            modifier = Modifier.padding(top = 24.dp),
            text = "Your data stays on this phone. No server, no login, no bank connection.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
