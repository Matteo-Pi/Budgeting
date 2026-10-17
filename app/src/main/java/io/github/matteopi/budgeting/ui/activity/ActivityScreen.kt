package io.github.matteopi.budgeting.ui.activity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton
import io.github.matteopi.budgeting.ui.receipts.ReceiptIcons

/** The Activity tab: all transactions grouped by day, with filters and an edit sheet (WP-U2). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(
    onAddTransaction: () -> Unit,
    onOpenReceipt: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    onScanReceipt: () -> Unit = {},
    viewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchOpen by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<ActivityRow?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = { SectionSwitchButton(AppSection.BUDGETING) },
                actions = {
                    IconButton(onClick = onScanReceipt) {
                        Icon(ReceiptIcons.Camera, contentDescription = "Scan receipt")
                    }
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(
                            if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTransaction) {
                Icon(Icons.Default.Add, contentDescription = "Add transaction")
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                item {
                    ActivityFiltersBar(
                        filters = state.filters,
                        accounts = state.accounts,
                        categories = state.categories,
                        searchOpen = searchOpen,
                        onAccountFilter = viewModel::setAccountFilter,
                        onCategoryFilter = viewModel::setCategoryFilter,
                        onDirectionFilter = viewModel::setDirectionFilter,
                        onSearch = viewModel::setSearch,
                    )
                }

                if (state.groups.isEmpty()) {
                    item {
                        EmptyState(
                            message = if (state.hasNoTransactions) {
                                "No transactions yet — tap + to add one."
                            } else {
                                "Nothing matches these filters."
                            },
                        )
                    }
                }

                state.groups.forEach { group ->
                    stickyHeader {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(group.rows) { row ->
                        ActivityRowView(
                            row = row,
                            categoriesById = state.categories.associateBy { it.id },
                            accountsById = state.accounts.associateBy { it.id },
                            onClick = { editingRow = row },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }

    TransactionEditHost(
        row = editingRow,
        onDismiss = { editingRow = null },
        controller = viewModel.editing,
        accounts = state.accounts,
        categories = state.categories,
        today = state.today,
        onOpenReceipt = onOpenReceipt,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityFiltersBar(
    filters: ActivityFilters,
    accounts: List<Account>,
    categories: List<Category>,
    searchOpen: Boolean,
    onAccountFilter: (Long?) -> Unit,
    onCategoryFilter: (Long?) -> Unit,
    onDirectionFilter: (DirectionFilter) -> Unit,
    onSearch: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (searchOpen) {
            OutlinedTextField(
                value = filters.search,
                onValueChange = onSearch,
                label = { Text("Search label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                DropdownFilterChip(
                    label = accounts.firstOrNull { it.id == filters.accountId }?.name ?: "Account",
                    selected = filters.accountId != null,
                    options = accounts.map { it.id to it.name },
                    onSelect = onAccountFilter,
                )
            }
            item {
                DropdownFilterChip(
                    label = categories.firstOrNull { it.id == filters.categoryId }?.name ?: "Category",
                    selected = filters.categoryId != null,
                    options = categories.map { it.id to it.name },
                    onSelect = onCategoryFilter,
                )
            }
            item {
                FilterChip(
                    selected = filters.direction == DirectionFilter.IN,
                    onClick = {
                        onDirectionFilter(if (filters.direction == DirectionFilter.IN) DirectionFilter.ALL else DirectionFilter.IN)
                    },
                    label = { Text("In") },
                )
            }
            item {
                FilterChip(
                    selected = filters.direction == DirectionFilter.OUT,
                    onClick = {
                        onDirectionFilter(if (filters.direction == DirectionFilter.OUT) DirectionFilter.ALL else DirectionFilter.OUT)
                    },
                    label = { Text("Out") },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownFilterChip(
    label: String,
    selected: Boolean,
    options: List<Pair<Long, String>>,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected, onClick = { expanded = true }, label = { Text(label) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { onSelect(null); expanded = false })
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}
