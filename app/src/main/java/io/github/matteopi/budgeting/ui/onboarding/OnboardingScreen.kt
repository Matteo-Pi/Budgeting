package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.ui.components.PlaceholderScreen

/** First-run flow: welcome, accounts, zero point, term dates, money in/out, estimates (WP-U6). */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(title = "Welcome", modifier = modifier)
}
