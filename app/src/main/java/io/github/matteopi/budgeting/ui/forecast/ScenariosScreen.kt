package io.github.matteopi.budgeting.ui.forecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.EmptyState

/** What-if scenario list (docs/PLAN.md §6.5 "What-if"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenariosScreen(
    onBack: () -> Unit,
    onOpenScenario: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScenariosViewModel = viewModel(factory = ScenariosViewModel.Factory),
) {
    val scenarios by viewModel.scenarios.collectAsStateWithLifecycle()
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("What-if") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenScenario(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New scenario")
            }
        },
    ) { innerPadding ->
        if (scenarios.isEmpty()) {
            EmptyState(
                message = "No what-if scenarios yet — try one to see how a change would affect your safe-to-spend.",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(scenarios, key = { it.id }) { scenario ->
                    ScenarioRow(
                        scenario = scenario,
                        onClick = { onOpenScenario(scenario.id) },
                        onDelete = { pendingDeleteId = scenario.id },
                    )
                }
            }
        }
    }

    val deleteId = pendingDeleteId
    if (deleteId != null) {
        ConfirmDialog(
            title = "Delete scenario?",
            text = "This removes the scenario and its items. This can't be undone.",
            onConfirm = {
                viewModel.delete(deleteId)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
            confirmLabel = "Delete",
        )
    }
}

@Composable
private fun ScenarioRow(scenario: Scenario, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(scenario.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${scenario.items.size} item${if (scenario.items.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${scenario.name}")
            }
        }
    }
}
