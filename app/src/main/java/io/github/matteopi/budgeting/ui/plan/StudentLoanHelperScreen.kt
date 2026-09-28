package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** Creates the three termly student loan instalments as scheduled items (WP-U3). */
@Composable
fun StudentLoanHelperScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Student loan helper", onBack = onBack, modifier = modifier)
}
