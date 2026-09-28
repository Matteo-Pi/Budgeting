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

/** Step 8 (optional): explains a statement import, then hands off to the import wizard (docs/PLAN.md §6.5). */
@Composable
fun StatementImportStep(
    onOpenStatementImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Past spending (optional)", style = MaterialTheme.typography.headlineMedium)
        Text(
            "If you export a CSV of your recent transactions from Santander online banking, you can " +
                "import it now so the forecast has some history to learn from straight away. You can " +
                "always do this later from Settings.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onOpenStatementImport) { Text("Import a statement") }
    }
}
