package io.github.matteopi.budgeting.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.analysis.Anomalies
import io.github.matteopi.budgeting.core.analysis.Anomaly
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.RiskSummary
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.ForecastService
import io.github.matteopi.budgeting.domain.ForecastSnapshot
import io.github.matteopi.budgeting.domain.PendingItem
import io.github.matteopi.budgeting.domain.PostingService
import io.github.matteopi.budgeting.domain.snapshot
import io.github.matteopi.budgeting.ui.activity.ActivityGrouping
import io.github.matteopi.budgeting.ui.activity.ActivityRow
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One account row on Home, with its balance already converted to the user's display terms. */
data class HomeAccountRow(val account: Account, val displayBalance: Money)

data class HomeUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
    val hasAccounts: Boolean = false,
    val accounts: List<HomeAccountRow> = emptyList(),
    /** "Your money": the balance of [balanceView] (the pool total or one account), in display terms. */
    val balanceDisplay: Money = Money.ZERO,
    /** The view actually shown in the "Your money" card (the persisted one, made safe for the accounts that exist). */
    val balanceView: AccountView = AccountView.Total,
    /** The non-archived accounts, for the chip that switches [balanceView]. */
    val accountChoices: List<Account> = emptyList(),
    val floorDisplay: Money = Money.ZERO,
    /** Always the all-accounts figure: safe-to-spend covers every account included in it. */
    val safeToSpend: SafeToSpend? = null,
    /** Set when the latest forecast run failed; any numbers shown are then from the last good forecast. */
    val forecastError: String? = null,
    val pendingConfirmations: List<PendingItem> = emptyList(),
    val needsPredictionCount: Int = 0,
    val risk: RiskSummary? = null,
    val anomalies: List<Anomaly> = emptyList(),
    val showReconcileNudge: Boolean = false,
    val overdueSinkingFunds: List<SinkingFund> = emptyList(),
    val sparkline: List<Money> = emptyList(),
    val recentActivity: List<ActivityRow> = emptyList(),
    val categoriesById: Map<Long, Category> = emptyMap(),
    val accountsById: Map<Long, Account> = emptyMap(),
)

/** Extension helpers: a [PendingItem] doesn't know its own label/amount/date directly. */
fun PendingItem.label(): String = when (this) {
    is PendingItem.Recurring -> rule.label
    is PendingItem.Scheduled -> item.label
}

fun PendingItem.defaultAmount(): Money = when (this) {
    is PendingItem.Recurring -> rule.amount
    is PendingItem.Scheduled -> item.amount
}

fun PendingItem.defaultDate(): LocalDate = when (this) {
    is PendingItem.Recurring -> date
    is PendingItem.Scheduled -> item.date
}

private fun PendingItem.sessionKey(): String = when (this) {
    is PendingItem.Recurring -> "rule:${rule.id}:$date"
    is PendingItem.Scheduled -> "item:${item.id}"
}

/** Home tab state and actions (docs/PLAN.md §6.5). */
class HomeViewModel(
    private val forecastService: ForecastService,
    private val postingService: PostingService,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val hiddenForSession = MutableStateFlow<Set<String>>(emptySet())

    private data class Source(val snapshot: ForecastSnapshot?, val pending: List<PendingItem>)

    // Everything on Home (the balance, the account rows, safe-to-spend and every card) is built
    // from this ONE forecast snapshot; no second subscription to the repositories is mixed in.
    private val sourceFlow = forecastService.state.mapLatest { state ->
        val snapshot = state.snapshot()
        val pending = if (snapshot != null) postingService.pendingConfirmations(snapshot.input.today) else emptyList()
        Source(snapshot, pending)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        sourceFlow,
        hiddenForSession,
        settingsStore.appSettings,
    ) { source, hidden, appSettings ->
        buildUiState(source.snapshot, source.pending, hidden, appSettings.balanceView)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun buildUiState(
        snapshot: ForecastSnapshot?,
        pending: List<PendingItem>,
        hiddenKeys: Set<String>,
        requestedBalanceView: AccountView,
    ): HomeUiState {
        if (snapshot == null) return HomeUiState()
        val input = snapshot.input
        val result = snapshot.result

        val zeroPoint = input.settings.zeroPoint
        val rawBalances = Balances.balancesByAccount(input.transactions, input.today)
        val liveAccounts = input.accounts.filter { !it.archived }.sortedBy { it.sortOrder }
        val accountRows = liveAccounts.map { account ->
            HomeAccountRow(
                account = account,
                displayBalance = Balances.toDisplay(
                    rawBalances[account.id] ?: Money.ZERO,
                    account.overdraftLimit,
                    zeroPoint,
                ),
            )
        }

        val balance = HomeMapping.balance(requestedBalanceView, input, result)

        val recentRows = ActivityGrouping.buildRows(
            input.transactions
                .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAtEpochMillis })
                .take(20),
        ).take(5)

        val visiblePending = pending.filterNot { it.sessionKey() in hiddenKeys }

        val overdueSinkingFunds = input.sinkingFunds.filter {
            it.status == SinkingFundStatus.ACTIVE && !it.targetDate.isAfter(input.today)
        }

        val anomalies = if (result != null) Anomalies.detect(input, result).take(3) else emptyList()

        val showReconcileNudge = ReconcileNudge.isStale(
            reconciliations = input.reconciliations,
            poolAccountIds = Balances.pool(input.accounts).map { it.id }.toSet(),
            today = input.today,
        )

        return HomeUiState(
            // A failed run with nothing to show is not "still working out": say so instead of spinning.
            loading = result == null && snapshot.error == null,
            today = input.today,
            zeroPoint = zeroPoint,
            hasAccounts = liveAccounts.isNotEmpty(),
            accounts = accountRows,
            balanceDisplay = balance.display,
            balanceView = balance.view,
            accountChoices = liveAccounts,
            floorDisplay = balance.floorDisplay,
            safeToSpend = result?.safeToSpend,
            forecastError = snapshot.error,
            pendingConfirmations = visiblePending,
            needsPredictionCount = result?.needsPrediction?.size ?: 0,
            risk = result?.risk,
            anomalies = anomalies,
            showReconcileNudge = showReconcileNudge,
            overdueSinkingFunds = overdueSinkingFunds,
            sparkline = balance.sparkline,
            recentActivity = recentRows,
            categoriesById = input.categories.associateBy { it.id },
            accountsById = input.accounts.associateBy { it.id },
        )
    }

    fun selectBalanceView(view: AccountView) {
        viewModelScope.launch { settingsStore.setBalanceView(view) }
    }

    fun confirmDefault(item: PendingItem) = viewModelScope.launch {
        postingService.confirm(item, item.defaultAmount(), item.defaultDate())
    }

    fun confirmEdited(item: PendingItem, amount: Money, date: LocalDate) = viewModelScope.launch {
        postingService.confirm(item, amount, date)
    }

    fun skip(item: PendingItem) = viewModelScope.launch {
        postingService.skip(item)
    }

    fun hideForSession(item: PendingItem) {
        hiddenForSession.value = hiddenForSession.value + item.sessionKey()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                HomeViewModel(
                    forecastService = app.container.forecastService,
                    postingService = app.container.postingService,
                    settingsStore = app.container.settingsStore,
                )
            }
        }
    }
}
