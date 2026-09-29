package io.github.matteopi.budgeting.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.matteopi.budgeting.ui.components.OptionalDateField
import io.github.matteopi.budgeting.ui.plan.Direction
import io.github.matteopi.budgeting.ui.plan.defaultPostingMode
import io.github.matteopi.budgeting.ui.plan.directionOf
import io.github.matteopi.budgeting.ui.plan.signedAmount

/** Step 5: the student loan helper and any other regular income (docs/PLAN.md §6.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyInStep(
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
        Text("Money coming in", style = MaterialTheme.typography.headlineSmall)

        Text("Student loan", style = MaterialTheme.typography.titleMedium)
        Text(
            "Creates three instalments in the \"${state.studentLoanCategory?.name ?: "Student loan"}\" " +
                "category, dated to your next three terms. If you haven't entered that many terms, " +
                "pick the missing dates yourself. You'll confirm each when it arrives.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.studentLoanCreated) {
            Text("Instalments saved.", color = MaterialTheme.colorScheme.primary)
        } else {
            AccountPicker(
                accounts = state.accounts,
                selectedAccountId = state.loanAccountId,
                onSelect = viewModel::setLoanAccountId,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Split a year's total instead", modifier = Modifier.weight(1f))
                Switch(checked = state.loanSplitMode, onCheckedChange = viewModel::setLoanSplitMode)
            }
            if (state.loanSplitMode) {
                OutlinedTextField(
                    value = state.loanTotalText,
                    onValueChange = viewModel::setLoanTotalText,
                    label = { Text("Year's total (£)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.loanInstalments.forEachIndexed { index, instalment ->
                Column {
                    Text("Instalment ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    OptionalDateField(
                        label = "Date",
                        date = instalment.date,
                        onDateChange = { viewModel.setLoanInstalmentDate(index, it) },
                        initialDate = state.today,
                    )
                    if (state.loanSplitMode) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Amount", modifier = Modifier.weight(1f))
                            MoneyText(amount = state.loanSplitAmounts.getOrElse(index) { Money.ZERO })
                        }
                    } else {
                        OutlinedTextField(
                            value = instalment.amountText,
                            onValueChange = { viewModel.setLoanInstalmentAmountText(index, it) },
                            label = { Text("Amount (£)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            val loanError = state.loanErrorMessage
            if (loanError != null) {
                Text(loanError, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = viewModel::saveStudentLoanInstalments) {
                Text("Save the three instalments")
            }
        }

        HorizontalDivider()

        Text("Other regular income", style = MaterialTheme.typography.titleMedium)
        Text(
            "A part-time job, a bursary, family support — anything else that comes in regularly.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val incomeRules = state.recurringRules.filter { directionOf(it.amount, it.toAccountId) == Direction.IN }
        incomeRules.forEach { rule -> RecurringRuleRow(rule = rule, onDelete = { viewModel.deleteRecurringRule(rule.id) }) }
        OtherIncomeForm(state = state, onAdd = viewModel::addRecurringRule)
    }
}

@Composable
private fun RecurringRuleRow(rule: RecurringRule, onDelete: () -> Unit) {
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
private fun OtherIncomeForm(state: OnboardingUiState, onAdd: (RecurringRule) -> Unit) {
    var label by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(Frequency.MONTHLY) }
    var date by remember { mutableStateOf(state.today) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var categoryId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.accounts, state.budgetSettings.defaultAccountId) {
        if (accountId == null) accountId = DefaultAccount.resolveId(state.accounts, state.budgetSettings.defaultAccountId)
    }
    val incomeCategories = state.categories.filter { it.flow == CategoryFlow.IN && !it.archived }
    LaunchedEffect(incomeCategories) { if (categoryId == null) categoryId = incomeCategories.firstOrNull()?.id }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label (e.g. Part-time job)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("Amount (£)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { frequency = Frequency.WEEKLY }, enabled = frequency != Frequency.WEEKLY) {
                Text("Every week")
            }
            OutlinedButton(onClick = { frequency = Frequency.MONTHLY }, enabled = frequency != Frequency.MONTHLY) {
                Text("Every month")
            }
        }
        DateField(label = "Next date", date = date, onDateChange = { date = it })
        AccountPicker(accounts = state.accounts, selectedAccountId = accountId, onSelect = { accountId = it })
        CategoryPicker(categories = incomeCategories, selectedCategoryId = categoryId, onSelect = { categoryId = it })
        val amount = Money.parse(amountText)
        val canAdd = label.isNotBlank() && amount != null && !amount.isZero && accountId != null && categoryId != null
        Button(
            onClick = {
                onAdd(
                    RecurringRule(
                        label = label.trim(),
                        accountId = accountId!!,
                        categoryId = categoryId,
                        amount = Direction.IN.signedAmount(amount!!.abs()),
                        frequency = frequency,
                        startDate = date,
                        postingMode = Direction.IN.defaultPostingMode(),
                    ),
                )
                label = ""
                amountText = ""
            },
            enabled = canAdd,
        ) { Text("Add income") }
    }
}
