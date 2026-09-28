package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * First-run setup: a stepper of 10 screens with Back/Skip/Next and a progress bar
 * (docs/PLAN.md §6.5). Each step persists what it collects immediately, via [OnboardingViewModel],
 * so leaving mid-way keeps what was entered. [onOpenStatementImport] opens the statement import
 * wizard for the optional step; the last step marks onboarding done and calls [onFinished].
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenStatementImport: () -> Unit = {},
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Scaffold(modifier = modifier) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            StepProgressBar(
                current = state.step.index,
                total = OnboardingStep.count,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.step) {
                    OnboardingStep.WELCOME -> WelcomeStep(modifier = Modifier.fillMaxSize())
                    OnboardingStep.ACCOUNTS -> AccountsStep(state = state, viewModel = viewModel)
                    OnboardingStep.MONEY_DISPLAY -> MoneyDisplayStep(state = state, viewModel = viewModel)
                    OnboardingStep.TERM_DATES -> TermDatesStep(state = state, viewModel = viewModel)
                    OnboardingStep.MONEY_IN -> MoneyInStep(state = state, viewModel = viewModel)
                    OnboardingStep.REGULAR_COSTS -> RegularCostsStep(state = state, viewModel = viewModel)
                    OnboardingStep.SPENDING_ESTIMATES -> SpendingEstimatesStep(state = state, viewModel = viewModel)
                    OnboardingStep.STATEMENT_IMPORT -> StatementImportStep(onOpenStatementImport = onOpenStatementImport)
                    OnboardingStep.WIDGET -> WidgetStep(modifier = Modifier.fillMaxSize())
                    OnboardingStep.DONE -> DoneStep(onFinish = viewModel::finish, modifier = Modifier.fillMaxSize())
                }
            }
            OnboardingFooter(
                step = state.step,
                onBack = viewModel::back,
                onSkip = viewModel::next,
                onNext = viewModel::next,
            )
        }
    }
}

@Composable
private fun StepProgressBar(current: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = ((current + 1).toFloat() / total).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun OnboardingFooter(
    step: OnboardingStep,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, enabled = step != OnboardingStep.WELCOME) { Text("Back") }
        if (step != OnboardingStep.DONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSkip) { Text("Skip") }
                Button(onClick = onNext) { Text("Next") }
            }
        }
    }
}
