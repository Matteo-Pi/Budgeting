package io.github.matteopi.budgeting.ui.quickadd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.ui.theme.BudgetingTheme

/**
 * Launched from the widget's "+" over a translucent, dimmed window (`Theme.Budgeting.QuickAdd`).
 * Real quick-add content (amount, category, account, presets) is WP-U1; for now this shows a
 * placeholder card and finishes when the dimmed area is tapped. It shows no balances, so it
 * needs no app-lock check.
 */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BudgetingTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Card(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Quick add coming soon",
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}
