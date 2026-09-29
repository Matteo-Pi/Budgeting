package io.github.matteopi.budgeting.ui.quickadd

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.ui.components.AmountKeypad
import java.time.LocalDate

/**
 * The quick-add card: Out/In toggle, big amount, presets, category chips, account + label +
 * date, keypad, Save. Used by [QuickAddActivity]. See docs/PLAN.md §6.6.
 */
@Composable
fun QuickAddScreen(
    state: QuickAddUiState,
    onFlowChange: (CategoryFlow) -> Unit,
    onKeypadKey: (String) -> Unit,
    onCategorySelect: (Long) -> Unit,
    onAccountSelect: (Long) -> Unit,
    onPresetSelect: (Preset) -> Unit,
    onLabelChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onSave: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        if (state.noAccounts) {
            NoAccountsContent(onOpenApp = onOpenApp)
        } else {
            QuickAddContent(
                state = state,
                onFlowChange = onFlowChange,
                onKeypadKey = onKeypadKey,
                onCategorySelect = onCategorySelect,
                onAccountSelect = onAccountSelect,
                onPresetSelect = onPresetSelect,
                onLabelChange = onLabelChange,
                onDateChange = onDateChange,
                onSave = onSave,
            )
        }
    }
}

@Composable
private fun NoAccountsContent(onOpenApp: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Add an account first to start logging spending.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenApp) { Text("Open app") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAddContent(
    state: QuickAddUiState,
    onFlowChange: (CategoryFlow) -> Unit,
    onKeypadKey: (String) -> Unit,
    onCategorySelect: (Long) -> Unit,
    onAccountSelect: (Long) -> Unit,
    onPresetSelect: (Preset) -> Unit,
    onLabelChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onSave: () -> Unit,
) {
    // Scrolls if the card is ever taller than the space above the keyboard (a small screen).
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.flow == CategoryFlow.OUT,
                onClick = { onFlowChange(CategoryFlow.OUT) },
                label = { Text("Out") },
            )
            FilterChip(
                selected = state.flow == CategoryFlow.IN,
                onClick = { onFlowChange(CategoryFlow.IN) },
                label = { Text("In") },
            )
            Spacer(Modifier.weight(1f))
            DateChip(date = state.date, today = state.today, onDateChange = onDateChange)
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = state.amountEntry.display,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        if (state.presets.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.presets.forEach { preset ->
                    AssistChip(
                        onClick = { onPresetSelect(preset) },
                        label = { Text("${preset.label} ${preset.amount.format()}") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.rankedCategories.forEach { category ->
                FilterChip(
                    selected = category.id == state.selectedCategoryId,
                    onClick = { onCategorySelect(category.id) },
                    label = { Text(category.name) },
                )
            }
            if (state.moreCategories.isNotEmpty()) {
                MoreCategoriesChip(
                    categories = state.moreCategories,
                    selectedCategoryId = state.selectedCategoryId,
                    onSelect = onCategorySelect,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccountChip(accounts = state.accounts, selectedAccountId = state.selectedAccountId, onSelect = onAccountSelect)
            LabelField(label = state.label, onLabelChange = onLabelChange, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // While the keyboard is up (typing a label) the amount keypad isn't needed; without it the card
        // fits above the keyboard with Save still visible.
        if (!WindowInsets.isImeVisible) {
            AmountKeypad(onKey = onKeypadKey, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}
