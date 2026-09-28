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
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.RiskSummary
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.domain.ForecastService
import io.github.matteopi.budgeting.domain.PendingItem
import io.github.matteopi.budgeting.domain.PostingService
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
    val poolBalanceDisplay: Money = Money.ZERO,
    val poolOverdraft: Money = Money.ZERO,
    val floorDisplay: Money = Money.ZERO,
    val safeToSpend: SafeToSpend? = null,
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
) : ViewModel() {

    private val hiddenForSession = MutableStateFlow<Set<String>>(emptySet())

    private val pendingConfirmationsFlow = forecastService.inputFlow
        .mapLatest { input -> postingService.pendingConfirmations(input.today) }

    val uiState: StateFlow<HomeUiState> = combine(
        forecastService.inputFlow,
        forecastService.forecast,
        pendingConfirmationsFlow,
        hiddenForSession,
    ) { input, result, pending, hidden ->
        buildUiState(input, result, pending, hidden)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun buildUiState(
        input: ForecastInput,
        result: ForecastResult?,
        pending: List<PendingItem>,
        hiddenKeys: Set<String>,
    ): HomeUiState {
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

        val pool = Balances.pool(input.accounts)
        val poolOverdraft = Balances.poolOverdraft(input.accounts)
        val poolRaw = result?.poolBalanceNow ?: Balances.poolBalance(input.accounts, input.transactions, input.today)
        val poolDisplay = Balances.toDisplay(poolRaw, poolOverdraft, zeroPoint)

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
            poolAccountIds = pool.map { it.id }.toSet(),
            today = input.today,
        )

        val sparkline = result?.days
            ?.filterIndexed { index, _ -> (index + 1) % 7 == 0 }
            ?.take(8)
            ?.map { Balances.toDisplay(it.expectedBalance, poolOverdraft, zeroPoint) }
            ?: emptyList()

        val floorRaw = result?.floor ?: Balances.floor(input.accounts, zeroPoint)
        val floorDisplay = Balances.toDisplay(floorRaw, poolOverdraft, zeroPoint)

        return HomeUiState(
            loading = result == null,
            today = input.today,
            zeroPoint = zeroPoint,
            hasAccounts = liveAccounts.isNotEmpty(),
            accounts = accountRows,
            poolBalanceDisplay = poolDisplay,
            poolOverdraft = poolOverdraft,
            floorDisplay = floorDisplay,
            safeToSpend = result?.safeToSpend,
            pendingConfirmations = visiblePending,
            needsPredictionCount = result?.needsPrediction?.size ?: 0,
            risk = result?.risk,
            anomalies = anomalies,
            showReconcileNudge = showReconcileNudge,
            overdueSinkingFunds = overdueSinkingFunds,
            sparkline = sparkline,
            recentActivity = recentRows,
            categoriesById = input.categories.associateBy { it.id },
            accountsById = input.accounts.associateBy { it.id },
        )
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
                )
            }
        }
    }
}
