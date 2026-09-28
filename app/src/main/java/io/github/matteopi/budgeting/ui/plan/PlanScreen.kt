package io.github.matteopi.budgeting.ui.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** The Plan tab: hub linking to money in/out, one-off dates, term dates, funds, estimates (WP-U3). */
@Composable
fun PlanScreen(
    onOpenRecurring: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenTermDates: () -> Unit,
    onOpenStudentLoanHelper: () -> Unit,
    onOpenSinkingFunds: () -> Unit,
    onOpenPredictions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Plan", modifier = modifier)
}
