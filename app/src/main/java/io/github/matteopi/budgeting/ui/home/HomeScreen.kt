package io.github.matteopi.budgeting.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.analysis.Anomaly
import io.github.matteopi.budgeting.core.forecast.RiskSummary
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.displayName
import io.github.matteopi.budgeting.domain.PendingItem
import io.github.matteopi.budgeting.ui.activity.ActivityRow
import io.github.matteopi.budgeting.ui.activity.ActivityRowView
import io.github.matteopi.budgeting.ui.activity.toShortLabel
import io.github.matteopi.budgeting.ui.components.AccountViewChip
import io.github.matteopi.budgeting.ui.components.ForecastErrorCard
import io.github.matteopi.budgeting.ui.components.MoneyText
import io.github.matteopi.budgeting.ui.components.SectionCard
import java.time.LocalDate

/** The Home tab: safe-to-spend hero, cards, accounts, mini forecast and recent activity (WP-U2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onOpenAccountEdit: (Long?) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenForecast: () -> Unit,
    onOpenPredictions: () -> Unit,
    onAddTransaction: () -> Unit,
    onOpenSinkingFunds: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingPending by remember { mutableStateOf<PendingItem?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Budgeting") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.forecastError?.let { error ->
                item { ForecastErrorCard(error) }
            }

            item { SafeToSpendHero(loading = state.loading, safeToSpend = state.safeToSpend) }

            item {
                BalanceCard(
                    balance = state.balanceDisplay,
                    zeroPoint = state.zeroPoint,
                    view = state.balanceView,
                    accountChoices = state.accountChoices,
                    onSelectView = viewModel::selectBalanceView,
                )
            }

            items(state.pendingConfirmations) { pendingItem ->
                PendingConfirmationCard(
                    item = pendingItem,
                    onConfirm = { viewModel.confirmDefault(pendingItem) },
                    onEdit = { editingPending = pendingItem },
                    onNotYet = { viewModel.hideForSession(pendingItem) },
                    onSkip = { viewModel.skip(pendingItem) },
                )
            }

            if (state.needsPredictionCount > 0) {
                item { NeedsPredictionCard(count = state.needsPredictionCount, onClick = onOpenPredictions) }
            }

            val risk = state.risk
            if (risk != null && risk.probBelowFloor >= 0.10) {
                item { RiskCard(risk = risk, safeToSpend = state.safeToSpend) }
            }

            items(state.anomalies) { anomaly -> AnomalyCard(anomaly) }

            if (state.showReconcileNudge) {
                item { ReconcileNudgeCard() }
            }

            if (state.overdueSinkingFunds.isNotEmpty()) {
                item { OverdueSinkingFundsCard(funds = state.overdueSinkingFunds, onClick = onOpenSinkingFunds) }
            }

            if (!state.loading && !state.hasAccounts) {
                item { FirstAccountCard(onClick = { onOpenAccountEdit(null) }) }
            }

            if (state.accounts.isNotEmpty()) {
                item {
                    AccountsSection(
                        accounts = state.accounts,
                        zeroPoint = state.zeroPoint,
                        onOpenAccount = onOpenAccount,
                        onAddAccount = { onOpenAccountEdit(null) },
                    )
                }
            }

            if (state.sparkline.size >= 2) {
                item {
                    SectionCard(title = "Next 8 weeks · ${state.balanceView.displayName(state.accountChoices)}") {
                        HomeSparkline(
                            points = state.sparkline,
                            floor = state.floorDisplay,
                            onClick = onOpenForecast,
                        )
                    }
                }
            }

            item {
                RecentActivitySection(
                    rows = state.recentActivity,
                    categoriesById = state.categoriesById,
                    accountsById = state.accountsById,
                    onSeeAll = onOpenActivity,
                )
            }
        }
    }

    val pendingToEdit = editingPending
    if (pendingToEdit != null) {
        PendingEditDialog(
            item = pendingToEdit,
            today = state.today,
            onDismiss = { editingPending = null },
            onConfirm = { amount, date ->
                viewModel.confirmEdited(pendingToEdit, amount, date)
                editingPending = null
            },
        )
    }
}

@Composable
private fun SafeToSpendHero(loading: Boolean, safeToSpend: SafeToSpend?) {
    val sts: SafeToSpend? = safeToSpend
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = "Safe to spend", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Across all accounts included in safe-to-spend",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(8.dp)
            if (sts == null) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(12.dp)
                        Text("Working out your numbers…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(
                        text = "Safe to spend isn't available right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (sts.shortfall.isPositive) {
                val date = sts.bindingDate ?: sts.horizonEnd
                Text(
                    text = "Even with no spending you'll be ${sts.shortfall.format(showPence = false)} short",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "on ${date.toShortLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HeroNextIncome(sts, color = MaterialTheme.colorScheme.error)
            } else {
                val detail = HomeMapping.heroDetail(sts)
                Text(
                    text = "${sts.weekly.format(showPence = false)} / week",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = detail.perDayLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HeroNextIncome(sts, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HeroNextIncome(safeToSpend: SafeToSpend, color: Color) {
    val line = HomeMapping.heroDetail(safeToSpend).nextIncomeLine ?: return
    Text(text = line, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun BalanceCard(
    balance: Money,
    zeroPoint: ZeroPoint,
    view: AccountView,
    accountChoices: List<Account>,
    onSelectView: (AccountView) -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Your money", style = MaterialTheme.typography.labelLarge)
            if (accountChoices.size >= 2) {
                AccountViewChip(accounts = accountChoices, selected = view, onSelect = onSelectView)
            }
        }
        MoneyText(
            amount = balance,
            modifier = Modifier.testTag("your-money"),
            style = MaterialTheme.typography.headlineMedium,
            isBalance = true,
        )
        if (zeroPoint == ZeroPoint.OVERDRAFT_LIMIT) {
            Text(
                text = "above your overdraft limit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingConfirmationCard(
    item: PendingItem,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onNotYet: () -> Unit,
    onSkip: () -> Unit,
) {
    SectionCard {
        Text(
            text = "Did your ${item.defaultAmount().abs().format(showPence = false)} ${item.label()} arrive?",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm) { Text("Confirm") }
            OutlinedButton(onClick = onEdit) { Text("Edit") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onNotYet) { Text("Not yet") }
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun NeedsPredictionCard(count: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 1) "1 spending estimate needed" else "$count spending estimates needed",
                style = MaterialTheme.typography.bodyLarge,
            )
            Icon(Icons.Default.Warning, contentDescription = null)
        }
    }
}

@Composable
private fun RiskCard(risk: RiskSummary, safeToSpend: SafeToSpend?) {
    val percent = (risk.probBelowFloor * 100).toInt()
    val date = safeToSpend?.horizonEnd
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(12.dp)
            val text = if (date != null) {
                "$percent% chance of going below your floor before ${date.toShortLabel()}"
            } else {
                "$percent% chance of going below your floor"
            }
            Text(text = text, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun AnomalyCard(anomaly: Anomaly) {
    SectionCard {
        Text(text = anomaly.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReconcileNudgeCard() {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(12.dp)
            Text(
                text = "You haven't checked a balance in a while — open an account and tap Update balance.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun OverdueSinkingFundsCard(funds: List<SinkingFund>, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DateRange, contentDescription = null)
                Spacer(8.dp)
                Text(
                    text = if (funds.size == 1) "1 sinking fund is overdue" else "${funds.size} sinking funds are overdue",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(4.dp)
            funds.take(3).forEach { fund ->
                Text(
                    text = "${fund.name} — target date was ${fund.targetDate.toShortLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FirstAccountCard(onClick: () -> Unit) {
    SectionCard {
        Text("Add your first account", style = MaterialTheme.typography.titleMedium)
        Spacer(4.dp)
        Text(
            text = "Add a current or savings account to start tracking your safe-to-spend.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(12.dp)
        Button(onClick = onClick) { Text("Add account") }
    }
}

@Composable
private fun AccountsSection(
    accounts: List<HomeAccountRow>,
    zeroPoint: ZeroPoint,
    onOpenAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
) {
    SectionCard(title = "Accounts") {
        accounts.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAccount(row.account.id) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.account.name, style = MaterialTheme.typography.bodyLarge)
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(amount = row.displayBalance, isBalance = true)
                    if (zeroPoint == ZeroPoint.OVERDRAFT_LIMIT) {
                        Text(
                            text = "above overdraft",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = onAddAccount, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(4.dp)
            Text("Add account")
        }
    }
}

@Composable
private fun RecentActivitySection(
    rows: List<ActivityRow>,
    categoriesById: Map<Long, Category>,
    accountsById: Map<Long, Account>,
    onSeeAll: () -> Unit,
) {
    SectionCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        if (rows.isEmpty()) {
            Text(
                text = "No transactions yet — tap + to add one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                ActivityRowView(
                    row = row,
                    categoriesById = categoriesById,
                    accountsById = accountsById,
                    onClick = onSeeAll,
                )
            }
        }
    }
}

@Composable
private fun Spacer(size: Dp) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(size))
}
