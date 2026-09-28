package io.github.matteopi.budgeting

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.testing.Screenshots
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the device-test pipeline works: a fresh install opens on the first onboarding step. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshInstallOpensOnboarding() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Welcome to Budgeting")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Welcome to Budgeting").assertExists()
        Screenshots.take("smoke-01-welcome")
    }
}
