package io.github.matteopi.budgeting.ui.planner.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.EmptyState

/**
 * A Planner tab that a later build brings (docs/PLAN.md §13.10): its top bar, and what it will hold and when.
 */
@Composable
fun PlannerComingSoonScreen(
    title: String,
    message: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { PlannerTabTopBar(title = title, onOpenSettings = onOpenSettings) },
    ) { innerPadding ->
        EmptyState(message, modifier = Modifier.padding(innerPadding).fillMaxSize())
    }
}
