package io.github.matteopi.budgeting

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import io.github.matteopi.budgeting.ui.navigation.BudgetNavHost
import io.github.matteopi.budgeting.ui.navigation.Route
import io.github.matteopi.budgeting.ui.theme.BudgetingTheme
import kotlinx.coroutines.flow.first

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires it (docs/PLAN.md §6.9).
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BudgetApp).container
        setContent {
            BudgetingTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val onboardingDone by produceState<Boolean?>(initialValue = null) {
                        value = container.settingsStore.appSettings.first().onboardingDone
                    }
                    val done = onboardingDone
                    if (done == null) {
                        LoadingScreen()
                    } else {
                        val navController = rememberNavController()
                        BudgetNavHost(
                            navController = navController,
                            startDestination = if (done) Route.Home else Route.Onboarding,
                            container = container,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
