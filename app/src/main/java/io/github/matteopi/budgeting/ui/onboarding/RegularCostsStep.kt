package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.data.repo.DefaultAccount
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.CategoryPicker
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.plan.Direction
import io.github.matteopi.budgeting.ui.plan.defaultPostingMode
import io.github.matteopi.budgeting.ui.plan.directionOf
import io.github.matteopi.budgeting.ui.plan.signedAmount

/** Step 6: a quick list builder for rent, bills and subscriptions, as AUTO recurring rules (docs/PLAN.md §6.5). */
@Composable
fun RegularCostsStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Regular costs", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Rent, phone, subscriptions — anything that goes out on a schedule.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val costRules = state.recurringRules.filter { directionOf(it.amount, it.toAccountId) == Direction.OUT }
        costRules.forEach { rule -> CostRow(rule = rule, onDelete = { viewModel.deleteRecurringRule(rule.id) }) }
        RegularCostForm(state = state, onAdd = viewModel::addRecurringRule)
    }
}

@Composable
private fun CostRow(rule: RecurringRule, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.label, style = MaterialTheme.typography.bodyLarge)
                MoneyText(amount = rule.amount, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun RegularCostForm(state: OnboardingUiState, onAdd: (RecurringRule) -> Unit) {
    var label by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(Frequency.MONTHLY) }
    var date by remember { mutableStateOf(state.today) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var categoryId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.accounts, state.budgetSettings.defaultAccountId) {
        if (accountId == null) accountId = DefaultAccount.resolveId(state.accounts, state.budgetSettings.defaultAccountId)
    }
    val outCategories = state.categories.filter { it.flow == CategoryFlow.OUT && !it.archived }
    LaunchedEffect(outCategories) {
        if (categoryId == null) categoryId = outCategories.firstOrNull { it.name == "Rent & bills" }?.id
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        REGULAR_COST_SUGGESTIONS.forEach { suggestion ->
            AssistChip(
                onClick = {
                    label = suggestion.label
                    categoryId = outCategories.firstOrNull { it.name == suggestion.categoryName }?.id ?: categoryId
                },
                label = { Text(suggestion.label) },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("Amount (£)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { frequency = Frequency.WEEKLY }, enabled = frequency != Frequency.WEEKLY) {
                Text("Every week")
            }
            OutlinedButton(onClick = { frequency = Frequency.MONTHLY }, enabled = frequency != Frequency.MONTHLY) {
                Text("Every month, on day ${date.dayOfMonth}")
            }
        }
        DateField(label = "Next date", date = date, onDateChange = { date = it })
        AccountPicker(accounts = state.accounts, selectedAccountId = accountId, onSelect = { accountId = it })
        CategoryPicker(categories = outCategories, selectedCategoryId = categoryId, onSelect = { categoryId = it })
        val amount = Money.parse(amountText)
        val canAdd = label.isNotBlank() && amount != null && !amount.isZero && accountId != null && categoryId != null
        Button(
            onClick = {
                onAdd(
                    RecurringRule(
                        label = label.trim(),
                        accountId = accountId!!,
                        categoryId = categoryId,
                        amount = Direction.OUT.signedAmount(amount!!.abs()),
                        frequency = frequency,
                        startDate = date,
                        postingMode = Direction.OUT.defaultPostingMode(),
                    ),
                )
                label = ""
                amountText = ""
            },
            enabled = canAdd,
        ) { Text("Add cost") }
    }
}
