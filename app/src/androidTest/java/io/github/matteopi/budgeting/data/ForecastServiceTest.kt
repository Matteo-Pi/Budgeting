package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.FlowSource
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.awaitReady
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [io.github.matteopi.budgeting.domain.ForecastService] fed by the real repositories: the single
 * `state` snapshot every screen draws from. Regression tests for "Your money £0 / £100 short".
 */
@RunWith(AndroidJUnit4::class)
class ForecastServiceTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    private suspend fun addStudentLoanInstalments(accountId: Long, pounds: Long = 1_000) {
        val loan = DataTestSupport.category("Student loan")
        for (n in 1..3) {
            container.planRepository.addScheduledItem(
                ScheduledItem(
                    label = "Student loan $n",
                    accountId = accountId,
                    categoryId = loan.id,
                    amount = Money.pounds(pounds),
                    date = today,
                    postingMode = PostingMode.CONFIRM,
                    isMajorIncome = true,
                ),
            )
        }
    }

    // ---- the pool ------------------------------------------------------------------------------

    @Test
    fun withNoAccountsTheStateIsReadyWithAnEmptyPool() = runBlocking<Unit> {
        val ready = awaitReady("Ready with no accounts") { it.input.accounts.isEmpty() }

        assertEquals(Money.ZERO, ready.result.poolBalanceNow)
        assertEquals(Money.ZERO, ready.result.poolOverdraft)
        assertTrue(ready.result.accountForecasts.isEmpty())
        assertEquals(today, ready.input.today)
        assertEquals(today, ready.result.today)
    }

    @Test
    fun theUsersThreeAccountsGiveExactlyTheirTotalAndNoShortfall() = runBlocking<Unit> {
        val budget = DataTestSupport.seedThreeAccountBudget()

        val ready = awaitReady("the three-account pool") {
            it.input.accounts.size == 3 && it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL
        }

        val result = ready.result
        assertEquals(Money(747_930), result.poolBalanceNow, "£6,579.30 + £400.00 + £500.00")
        assertEquals(Money.pounds(1_500), result.poolOverdraft)
        assertEquals(Money.ZERO, result.floor, "the default zero point is an empty account")
        assertEquals(
            setOf(DataTestSupport.LLOYDS, DataTestSupport.SANTANDER_SAVINGS, DataTestSupport.SANTANDER_CURRENT),
            ready.input.accounts.map { it.name }.toSet(),
        )
        assertEquals(3, ready.input.transactions.size, "one opening balance per account")

        assertEquals(3, result.accountForecasts.size)
        val forecastById = result.accountForecasts.associateBy { it.accountId }
        assertEquals(Money(657_930), forecastById.getValue(budget.lloyds.id).balanceNow)
        assertEquals(Money(40_000), forecastById.getValue(budget.santanderSavings.id).balanceNow)
        assertEquals(Money(50_000), forecastById.getValue(budget.santanderCurrent.id).balanceNow)
        assertEquals(Money.pounds(1_500), forecastById.getValue(budget.lloyds.id).overdraft)
        assertTrue(result.accountForecasts.all { it.inPool })

        assertTrue(result.safeToSpend.shortfall.isZero, "£7,479.30 covers the £100 buffer: no 'short' warning")
        assertTrue(result.safeToSpend.weekly.isPositive, "and there is something to spend")
        assertNotNull(result.risk, "the full model (with Monte Carlo) ran")
    }

    @Test
    fun theSnapshotsInputAndResultAgree() = runBlocking<Unit> {
        DataTestSupport.seedThreeAccountBudget()
        val ready = awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        val fromInput = Balances.poolBalance(ready.input.accounts, ready.input.transactions, ready.input.today)
        assertEquals(fromInput, ready.result.poolBalanceNow, "the number shown as 'Your money' matches the accounts beside it")
        assertEquals(ready.input.today, ready.result.today)

        val poolAccounts = ready.result.accountForecasts.filter { it.inPool }
        assertEquals(ready.result.poolBalanceNow, Money(poolAccounts.sumOf { it.balanceNow.pence }))
        // Per-account expected balances add up to the pool's, on every day (spot-check the last).
        val lastDay = ready.result.days.lastIndex
        assertEquals(
            ready.result.days[lastDay].expectedBalance,
            Money(poolAccounts.sumOf { it.days[lastDay].expectedBalance.pence }),
        )
    }

    @Test
    fun aNonPoolAccountIsForecastButNotInTheTotal() = runBlocking<Unit> {
        DataTestSupport.seedThreeAccountBudget()
        val cash = createAccount("Cash", opening = Money(2_000), includeInPool = false)

        val ready = awaitReady("cash outside the pool") { it.input.accounts.size == 4 && it.input.transactions.size == 4 }

        assertEquals(DataTestSupport.POOL_TOTAL, ready.result.poolBalanceNow, "cash is not part of your money")
        val forecast = ready.result.accountForecasts.single { it.accountId == cash.id }
        assertEquals(Money(2_000), forecast.balanceNow)
        assertTrue(!forecast.inPool)
    }

    @Test
    fun archivingAnAccountTakesItOutOfThePool() = runBlocking<Unit> {
        val budget = DataTestSupport.seedThreeAccountBudget()
        awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        container.accountRepository.archive(budget.santanderCurrent.id)

        val ready = awaitReady("the pool without the archived account") {
            it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL - DataTestSupport.SANTANDER_BALANCE
        }
        assertEquals(2, ready.result.accountForecasts.size, "archived accounts are not forecast")
        assertTrue(ready.result.accountForecasts.none { it.accountId == budget.santanderCurrent.id })
    }

    @Test
    fun theZeroPointSettingReachesTheForecast() = runBlocking<Unit> {
        DataTestSupport.seedThreeAccountBudget()
        val before = awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        container.settingsStore.setZeroPoint(ZeroPoint.OVERDRAFT_LIMIT)

        val after = awaitReady("the overdraft zero point") { it.input.settings.zeroPoint == ZeroPoint.OVERDRAFT_LIMIT }
        assertEquals(Money.pounds(-1_500), after.result.floor, "£0 is now the overdraft limit")
        assertEquals(DataTestSupport.POOL_TOTAL, after.result.poolBalanceNow, "raw balances never change with the zero point")
        assertTrue(
            after.result.safeToSpend.weekly > before.result.safeToSpend.weekly,
            "the arranged overdraft is extra room to spend",
        )
    }

    // ---- reacting to writes --------------------------------------------------------------------

    @Test
    fun addingATransactionProducesANewSnapshotThatReflectsIt() = runBlocking<Unit> {
        val budget = DataTestSupport.seedThreeAccountBudget()
        val groceries = DataTestSupport.category("Groceries")
        val first = awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        DataTestSupport.addNormal(budget.santanderCurrent.id, groceries.id, Money(-10_000), today, "Big shop")

        val second = awaitReady("a snapshot with the new transaction") { it.input.transactions.size == 4 }
        assertEquals(DataTestSupport.POOL_TOTAL - Money(10_000), second.result.poolBalanceNow)
        assertEquals(
            Money(40_000),
            second.result.accountForecasts.single { it.accountId == budget.santanderCurrent.id }.balanceNow,
        )
        assertTrue(second.input.transactions.any { it.label == "Big shop" })
        assertTrue(second.computedAtMillis >= first.computedAtMillis)
        assertTrue(second.result.safeToSpend.weekly < first.result.safeToSpend.weekly, "less money, less to spend")
    }

    @Test
    fun aTransferBetweenPoolAccountsMovesMoneyButNotTheTotal() = runBlocking<Unit> {
        val budget = DataTestSupport.seedThreeAccountBudget()
        awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        container.transactionRepository.addTransfer(
            budget.lloyds.id, budget.santanderSavings.id, Money.pounds(100), today, "Save",
        )

        val ready = awaitReady("a snapshot with the transfer") { it.input.transactions.size == 5 }
        assertEquals(DataTestSupport.POOL_TOTAL, ready.result.poolBalanceNow)
        val byId = ready.result.accountForecasts.associateBy { it.accountId }
        assertEquals(Money(647_930), byId.getValue(budget.lloyds.id).balanceNow)
        assertEquals(Money(50_000), byId.getValue(budget.santanderSavings.id).balanceNow)
    }

    @Test
    fun futureDatedTransactionsAreNotInYourMoneyYet() = runBlocking<Unit> {
        val budget = DataTestSupport.seedThreeAccountBudget()
        val groceries = DataTestSupport.category("Groceries")
        awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        DataTestSupport.addNormal(budget.lloyds.id, groceries.id, Money(-5_000), today.plusDays(3), "Next week")

        val ready = awaitReady("a snapshot with the future transaction") { it.input.transactions.size == 4 }
        assertEquals(DataTestSupport.POOL_TOTAL, ready.result.poolBalanceNow)
        assertTrue(
            ready.result.committedFlows.any { it.source == FlowSource.TRANSACTION && it.amount == Money(-5_000) },
            "it is reported as a known future flow instead",
        )
    }

    // ---- student loan and rent -----------------------------------------------------------------

    @Test
    fun threeStudentLoanInstalmentsDatedTodayGiveAPositiveSafeToSpend() = runBlocking<Unit> {
        val budget = DataTestSupport.seedThreeAccountBudget()
        val before = awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        addStudentLoanInstalments(budget.lloyds.id)

        val ready = awaitReady("the three instalments") { it.input.scheduledItems.size == 3 }
        val safe = ready.result.safeToSpend
        assertTrue(safe.weekly.isPositive, "safe to spend is not £0")
        assertTrue(safe.shortfall.isZero)
        assertTrue(safe.weekly > before.result.safeToSpend.weekly, "£3,000 on its way adds to what can be spent")

        val overdue = ready.result.committedFlows.filter { it.source == FlowSource.OVERDUE_CONFIRM }
        assertEquals(3, overdue.size, "unconfirmed instalments dated today count as arriving tomorrow")
        assertTrue(overdue.all { it.isMajorIncome && it.amount == Money.pounds(1_000) })
        assertNotNull(safe.nextMajorIncome)
        assertEquals(1, safe.daysUntilNextMajorIncome)
        assertEquals(DataTestSupport.POOL_TOTAL, ready.result.poolBalanceNow, "nothing is in the balance until confirmed")
    }

    @Test
    fun theInstalmentsTurnAShortfallIntoSomethingToSpend() = runBlocking<Unit> {
        // Nothing in the account: only the £100 buffer to make up, until the loan arrives.
        val account = createAccount("Current", opening = Money.ZERO)
        val without = awaitReady("an empty account") { it.input.accounts.size == 1 && it.input.transactions.size == 1 }
        assertEquals(Money.pounds(100), without.result.safeToSpend.shortfall)
        assertTrue(without.result.safeToSpend.weekly.isZero)

        addStudentLoanInstalments(account.id)

        val with = awaitReady("the three instalments") { it.input.scheduledItems.size == 3 }
        assertTrue(with.result.safeToSpend.shortfall.isZero, "no longer short")
        assertTrue(with.result.safeToSpend.weekly.isPositive, "safe to spend is not £0")
    }

    @Test
    fun confirmingTheInstalmentsMovesThemIntoTheBalanceWithoutChangingSafeToSpend() = runBlocking<Unit> {
        val budget = DataTestSupport.seedThreeAccountBudget()
        addStudentLoanInstalments(budget.lloyds.id)
        val pendingSnapshot = awaitReady("the three instalments") { it.input.scheduledItems.size == 3 }

        val pending = container.postingService.pendingConfirmations(today)
        assertEquals(3, pending.size)
        for (item in pending) container.postingService.confirm(item, Money.pounds(1_000), today)

        val confirmed = awaitReady("the instalments confirmed") {
            it.input.scheduledItems.all { item -> item.posted } && it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL + Money.pounds(3_000)
        }
        assertTrue(confirmed.result.committedFlows.none { it.source == FlowSource.OVERDUE_CONFIRM })
        assertEquals(
            pendingSnapshot.result.safeToSpend.weekly,
            confirmed.result.safeToSpend.weekly,
            "money that has arrived counts exactly as much as money that was about to arrive",
        )
    }

    @Test
    fun aRecurringRentRuleReducesSafeToSpend() = runBlocking<Unit> {
        val budget = DataTestSupport.seedThreeAccountBudget()
        val rent = DataTestSupport.category("Rent & bills")
        val before = awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        container.planRepository.addRecurringRule(
            RecurringRule(
                label = "Rent",
                accountId = budget.lloyds.id,
                categoryId = rent.id,
                amount = Money.pounds(-1_000),
                frequency = Frequency.MONTHLY,
                startDate = today.plusDays(2),
                postingMode = PostingMode.AUTO,
            ),
        )

        val after = awaitReady("the rent rule") { state -> state.input.recurringRules.any { it.label == "Rent" } }
        val rentFlows = after.result.committedFlows.filter { it.label == "Rent" }
        assertTrue(rentFlows.isNotEmpty(), "the rent shows up as committed flows")
        assertTrue(rentFlows.all { it.source == FlowSource.RECURRING && it.amount == Money.pounds(-1_000) })
        assertEquals(today.plusDays(2), rentFlows.first().date)
        assertTrue(
            after.result.safeToSpend.weekly < before.result.safeToSpend.weekly,
            "rent leaves less to spend: ${after.result.safeToSpend.weekly} vs ${before.result.safeToSpend.weekly}",
        )
        assertEquals(DataTestSupport.POOL_TOTAL, after.result.poolBalanceNow, "future rent is not in today's balance")
    }

    // ---- the on-demand entry points ------------------------------------------------------------

    @Test
    fun quickSkipsTheMonteCarloAndFullRunsIt() = runBlocking<Unit> {
        DataTestSupport.seedThreeAccountBudget()
        awaitReady("the three-account pool") { it.result.poolBalanceNow == DataTestSupport.POOL_TOTAL }

        val quick = container.forecastService.quick()
        val full = container.forecastService.full()

        assertNull(quick.risk)
        assertNotNull(full.risk)
        assertEquals(DataTestSupport.POOL_TOTAL, quick.poolBalanceNow)
        assertEquals(DataTestSupport.POOL_TOTAL, full.poolBalanceNow)
        assertEquals(quick.safeToSpend.weekly, full.safeToSpend.weekly, "safe to spend does not depend on the simulation")
    }
}
