package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint

/** Step 3: the zero point choice, plainly explained, and the safety buffer (docs/PLAN.md §6.5). */
@Composable
fun MoneyDisplayStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("How should we show your balance?", style = MaterialTheme.typography.headlineSmall)
        ZeroPointOption(
            label = "£0 = empty account",
            description = "The balance shown matches your bank app exactly. Going below £0 means " +
                "you're in your overdraft.",
            selected = state.budgetSettings.zeroPoint == ZeroPoint.ACCOUNT_BALANCE,
            onSelect = { viewModel.setZeroPoint(ZeroPoint.ACCOUNT_BALANCE) },
        )
        ZeroPointOption(
            label = "£0 = overdraft used up",
            description = "The balance shown adds your overdraft limit on top, so £0 means you've " +
                "used all of it and going below is a real problem.",
            selected = state.budgetSettings.zeroPoint == ZeroPoint.OVERDRAFT_LIMIT,
            onSelect = { viewModel.setZeroPoint(ZeroPoint.OVERDRAFT_LIMIT) },
        )
        Text("Safety buffer", style = MaterialTheme.typography.titleMedium)
        Text(
            "Safe-to-spend is worked out so this much is always kept in hand above zero, in case " +
                "something unexpected comes up.",
            style = MaterialTheme.typography.bodyMedium,
        )
        BufferField(buffer = state.budgetSettings.buffer, onChange = viewModel::setBuffer)
    }
}

@Composable
private fun ZeroPointOption(label: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BufferField(buffer: Money, onChange: (Money) -> Unit) {
    var text by remember(buffer) { mutableStateOf(buffer.format(showPence = false).removePrefix("£")) }
    val error = text.isNotBlank() && (Money.parse(text) == null || Money.parse(text)?.isNegative == true)
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            val money = Money.parse(newText)
            if (money != null && !money.isNegative) onChange(money)
        },
        label = { Text("Safety buffer (£)") },
        isError = error,
        modifier = Modifier.fillMaxWidth(),
    )
}
