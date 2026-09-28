package io.github.matteopi.budgeting.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.DateField
import io.github.matteopi.budgeting.ui.components.MoneyText

/** Creates the three termly student loan instalments as scheduled items (WP-U3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentLoanHelperScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StudentLoanHelperViewModel = viewModel(factory = StudentLoanHelperViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Student loan helper") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Creates three scheduled instalments in the \"${state.category?.name ?: "Student loan"}\" " +
                        "category, dated to your next three terms. Confirm each one when it arrives.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                AccountPicker(
                    accounts = state.accounts,
                    selectedAccountId = state.accountId,
                    onSelect = viewModel::setAccountId,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Split a year's total instead", modifier = Modifier.weight(1f))
                    Switch(checked = state.splitMode, onCheckedChange = viewModel::setSplitMode)
                }
            }
            if (state.splitMode) {
                item {
                    OutlinedTextField(
                        value = state.totalText,
                        onValueChange = viewModel::setTotalText,
                        label = { Text("Year's total (£)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            itemsIndexed(state.instalments) { index, instalment ->
                Column {
                    Text("Instalment ${index + 1}", style = MaterialTheme.typography.titleMedium)
                    DateField(
                        label = "Date",
                        date = instalment.date,
                        onDateChange = { viewModel.setInstalmentDate(index, it) },
                    )
                    if (state.splitMode) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Amount", modifier = Modifier.weight(1f))
                            MoneyText(amount = state.splitAmounts.getOrElse(index) { Money.ZERO })
                        }
                    } else {
                        OutlinedTextField(
                            value = instalment.amountText,
                            onValueChange = { viewModel.setInstalmentAmountText(index, it) },
                            label = { Text("Amount (£)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (state.errorMessage != null) {
                item {
                    Text(text = state.errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                    Text("Create the three instalments")
                }
            }
        }
    }
}
