package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests of the per-account forecast split (`docs/PLAN.md` §5.11a). */
class AccountSplitTest {
    private val today = LocalDate.of(2024, 6, 3) // Monday
    private val current = TestData.CURRENT
    private val savings = TestData.SAVINGS
    private val outside = TestData.OUTSIDE_POOL

    // --- helpers ------------------------------------------------------------------------------

    private fun opening(accountId: Long, amount: Money) =
        TestData.tx(accountId, amount, today.minusYears(1), null, kind = TxKind.OPENING_BALANCE)

    /** One transaction per week on [accountId] for the [amounts].size complete weeks before today (a Wednesday). */
    private fun weekly(accountId: Long, categoryId: Long, amounts: List<Money>): List<Transaction> =
        amounts.mapIndexed { i, amount ->
            TestData.tx(accountId, amount, today.minusWeeks((amounts.size - i).toLong()).plusDays(2), categoryId)
        }

    private fun pounds(vararg values: Long) = values.map { Money.pounds(it) }

    private fun run(input: ForecastInput) = ForecastEngine.run(input)

    private fun ForecastResult.account(id: Long): AccountForecast =
        accountForecasts.single { it.accountId == id }

    private fun AccountForecast.on(offset: Int): AccountDayForecast = days[offset]

    private fun transferItem(
        id: Long, from: Long, to: Long, pounds: Long, dayOffset: Int, mode: PostingMode = PostingMode.AUTO,
    ) = ScheduledItem(
        id = id, label = "Transfer $id", accountId = from, categoryId = null, amount = Money.pounds(pounds),
        date = today.plusDays(dayOffset.toLong()), postingMode = mode, toAccountId = to,
    )

    private fun assertPoolInvariant(result: ForecastResult) {
        val pool = result.accountForecasts.filter { it.inPool }
        for ((i, day) in result.days.withIndex()) {
            val sum = pool.sumOf { it.days[i].expectedBalance.pence }
            assertEquals(day.expectedBalance.pence, sum, "pool accounts must add up to the pool on ${day.date}")
        }
    }

    /** Total learned pence over the horizon that landed on [id]: end balance minus start (no committed flows in these inputs). */
    private fun learnedOn(result: ForecastResult, id: Long): Long {
        val forecast = result.account(id)
        return forecast.days.last().expectedBalance.pence - forecast.balanceNow.pence
    }

    /** Per-day rounding of the tier-2 shares moves a weekly total by a few pence; that is not what is under test. */
    private fun assertNear(expected: Long, actual: Long, tolerancePence: Long, message: String = "") {
        assertTrue(Math.abs(expected - actual) <= tolerancePence, "$message expected $expected +/- $tolerancePence but was $actual")
    }

    private fun accounts4(): List<Account> = TestData.accounts() + Account(
        id = 4, name = "Card", type = AccountType.CREDIT_CARD, overdraftLimit = Money.pounds(500),
    )

    // --- structure ----------------------------------------------------------------------------

    @Test
    fun coversExactlyTheNonArchivedAccountsOrderedByIdWithDaysAlignedToThePool() {
        val accounts = listOf(
            Account(id = 7, name = "Seven", type = AccountType.CURRENT, overdraftLimit = Money.pounds(300)),
            Account(id = 2, name = "Two", type = AccountType.SAVINGS, archived = true),
            Account(id = 5, name = "Five", type = AccountType.CASH, includeInSafeToSpend = false),
            Account(id = 3, name = "Three", type = AccountType.CURRENT),
        )
        val txs = listOf(opening(7, Money.pounds(70)), opening(2, Money.pounds(20)), opening(5, Money.pounds(50)), opening(3, Money.pounds(30)))
        val result = run(
            TestData.input(today, accounts = accounts, transactions = txs, settings = TestData.settings(forecastHorizonDays = 20), runMonteCarlo = false),
        )
        assertEquals(listOf(3L, 5L, 7L), result.accountForecasts.map { it.accountId })
        assertEquals(listOf(true, false, true), result.accountForecasts.map { it.inPool })
        assertEquals(listOf(Money.pounds(30), Money.pounds(50), Money.pounds(70)), result.accountForecasts.map { it.balanceNow })
        assertEquals(listOf(Money.ZERO, Money.ZERO, Money.pounds(300)), result.accountForecasts.map { it.overdraft })
        for (forecast in result.accountForecasts) {
            assertEquals(result.days.map { it.date }, forecast.days.map { it.date })
        }
        // The archived account's balance is not in the pool.
        assertEquals(Money.pounds(100), result.poolBalanceNow)
    }

    @Test
    fun todayEntryIsTheCurrentBalanceWithACollapsedBandWhenMonteCarloRan() {
        val txs = listOf(opening(current, Money.pounds(100)), opening(savings, Money.pounds(40)))
        val result = run(
            TestData.input(today, transactions = txs, settings = TestData.settings(forecastHorizonDays = 10, simulations = 60), runMonteCarlo = true),
        )
        for (forecast in result.accountForecasts) {
            val first = forecast.days.first()
            assertEquals(today, first.date)
            assertEquals(forecast.balanceNow, first.expectedBalance)
            assertEquals(forecast.balanceNow, first.p10)
            assertEquals(forecast.balanceNow, first.p50)
            assertEquals(forecast.balanceNow, first.p90)
        }
    }

    @Test
    fun percentilesAreNullForEveryAccountWhenMonteCarloIsSkipped() {
        val result = run(TestData.input(today, settings = TestData.settings(forecastHorizonDays = 10), runMonteCarlo = false))
        assertTrue(result.accountForecasts.isNotEmpty())
        for (forecast in result.accountForecasts) {
            assertTrue(forecast.days.all { it.p10 == null && it.p50 == null && it.p90 == null })
        }
    }

    // --- transfers ----------------------------------------------------------------------------

    @Test
    fun transferBetweenTwoPoolAccountsMovesMoneyBetweenThemAndLeavesThePoolUnchanged() {
        val txs = listOf(opening(current, Money.pounds(500)), opening(savings, Money.pounds(100)))
        val input = TestData.input(
            today, transactions = txs, scheduledItems = listOf(transferItem(1, current, savings, 120, 3)),
            settings = TestData.settings(forecastHorizonDays = 10),
        )
        val result = run(input)
        assertTrue(result.committedFlows.isEmpty(), "a pool-to-pool transfer is not a pool flow")
        val cur = result.account(current)
        val sav = result.account(savings)
        for (offset in 0..2) {
            assertEquals(Money.pounds(500), cur.on(offset).expectedBalance)
            assertEquals(Money.pounds(100), sav.on(offset).expectedBalance)
        }
        for (offset in 3..10) {
            assertEquals(Money.pounds(380), cur.on(offset).expectedBalance, "offset $offset")
            assertEquals(Money.pounds(220), sav.on(offset).expectedBalance, "offset $offset")
        }
        assertTrue(result.days.all { it.expectedBalance == Money.pounds(600) })
        assertPoolInvariant(result)
    }

    @Test
    fun transferFromAPoolAccountToANonPoolAccountLeavesThePoolAndArrivesThere() {
        val txs = listOf(opening(current, Money.pounds(500)), opening(outside, Money.pounds(10)))
        val input = TestData.input(
            today, transactions = txs, scheduledItems = listOf(transferItem(1, current, outside, 75, 2)),
            settings = TestData.settings(forecastHorizonDays = 6),
        )
        val result = run(input)
        assertEquals(listOf(Money.pounds(-75)), result.committedFlows.map { it.amount })
        assertEquals(Money.pounds(500), result.account(current).on(1).expectedBalance)
        assertEquals(Money.pounds(425), result.account(current).on(2).expectedBalance)
        assertEquals(Money.pounds(10), result.account(outside).on(1).expectedBalance)
        assertEquals(Money.pounds(85), result.account(outside).on(2).expectedBalance)
        assertEquals(Money.pounds(85), result.account(outside).on(6).expectedBalance)
        assertEquals(Money.pounds(425), result.days.last().expectedBalance)
        assertPoolInvariant(result)
    }

    @Test
    fun transferFromANonPoolAccountToAPoolAccountEntersThePoolAndLeavesThere() {
        val txs = listOf(opening(savings, Money.pounds(200)), opening(outside, Money.pounds(300)))
        val input = TestData.input(
            today, transactions = txs, scheduledItems = listOf(transferItem(1, outside, savings, 250, 4)),
            settings = TestData.settings(forecastHorizonDays = 8),
        )
        val result = run(input)
        assertEquals(listOf(Money.pounds(250)), result.committedFlows.map { it.amount })
        assertEquals(Money.pounds(200), result.account(savings).on(3).expectedBalance)
        assertEquals(Money.pounds(450), result.account(savings).on(4).expectedBalance)
        assertEquals(Money.pounds(300), result.account(outside).on(3).expectedBalance)
        assertEquals(Money.pounds(50), result.account(outside).on(4).expectedBalance)
        assertEquals(Money.pounds(450), result.days.last().expectedBalance)
        assertPoolInvariant(result)
    }

    @Test
    fun aRecurringTransferHitsBothLegsOnEveryOccurrence() {
        val rule = RecurringRule(
            id = 1, label = "Save", accountId = current, categoryId = null, amount = Money.pounds(20),
            frequency = Frequency.WEEKLY, startDate = today.plusDays(2), postingMode = PostingMode.AUTO, toAccountId = outside,
        )
        val txs = listOf(opening(current, Money.pounds(1000)), opening(outside, Money.ZERO))
        val result = run(TestData.input(today, transactions = txs, recurringRules = listOf(rule), settings = TestData.settings(forecastHorizonDays = 30)))
        for (offset in 0..30) {
            val occurrences: Long = if (offset < 2) 0L else ((offset - 2) / 7 + 1).toLong()
            assertEquals(Money.pounds(1000 - 20 * occurrences), result.account(current).on(offset).expectedBalance, "offset $offset")
            assertEquals(Money.pounds(20 * occurrences), result.account(outside).on(offset).expectedBalance, "offset $offset")
        }
        assertPoolInvariant(result)
    }

    @Test
    fun anOverdueTransferLandsOnTomorrowOnBothAccounts() {
        val txs = listOf(opening(current, Money.pounds(300)), opening(savings, Money.pounds(0)))
        val overdue = transferItem(1, current, savings, 50, -5, PostingMode.CONFIRM)
        val result = run(TestData.input(today, transactions = txs, scheduledItems = listOf(overdue), settings = TestData.settings(forecastHorizonDays = 5)))
        assertEquals(Money.pounds(300), result.account(current).on(0).expectedBalance)
        assertEquals(Money.pounds(250), result.account(current).on(1).expectedBalance)
        assertEquals(Money.pounds(50), result.account(savings).on(1).expectedBalance)
    }

    @Test
    fun aTransferToAnArchivedAccountOnlyHitsTheCoveredLeg() {
        val accounts = TestData.accounts() + Account(id = 9, name = "Old", type = AccountType.SAVINGS, archived = true)
        val txs = listOf(opening(current, Money.pounds(400)))
        val input = TestData.input(
            today, accounts = accounts, transactions = txs, scheduledItems = listOf(transferItem(1, current, 9, 60, 1)),
            settings = TestData.settings(forecastHorizonDays = 4),
        )
        val result = run(input)
        assertEquals(listOf(1L, 2L, 3L), result.accountForecasts.map { it.accountId })
        // The archived account is outside the pool, so the pool does lose the money.
        assertEquals(Money.pounds(340), result.days.last().expectedBalance)
        assertEquals(Money.pounds(340), result.account(current).on(4).expectedBalance)
        assertPoolInvariant(result)
    }

    @Test
    fun aFutureDatedTransferPairOfLedgerRowsIsSplitLikeAnyOtherFlow() {
        val group = "g1"
        val txs = listOf(
            opening(current, Money.pounds(100)),
            Transaction(id = 501, accountId = current, amount = Money.pounds(-30), date = today.plusDays(2), categoryId = null, kind = TxKind.TRANSFER, transferGroupId = group),
            Transaction(id = 502, accountId = outside, amount = Money.pounds(30), date = today.plusDays(2), categoryId = null, kind = TxKind.TRANSFER, transferGroupId = group),
        )
        val result = run(TestData.input(today, transactions = txs, settings = TestData.settings(forecastHorizonDays = 4)))
        assertEquals(Money.pounds(70), result.account(current).on(2).expectedBalance)
        assertEquals(Money.pounds(30), result.account(outside).on(2).expectedBalance)
        assertEquals(Money.pounds(70), result.days.last().expectedBalance)
        assertPoolInvariant(result)
    }

    // --- learned flows -----------------------------------------------------------------------

    @Test
    fun learnedFlowsFollowTheCategorysHistoricalShareOfEachAccount() {
        // Groceries: three quarters of the money history went through Current, one quarter Savings.
        val history = weekly(current, TestData.GROCERIES, pounds(-30, -30, -30, -30, -30, -30, -30, -30)) +
            weekly(savings, TestData.GROCERIES, pounds(-10, -10, -10, -10, -10, -10, -10, -10))
        val input = TestData.input(
            today, transactions = history, settings = TestData.settings(historyStartDate = today.minusWeeks(8), forecastHorizonDays = 70),
        )
        val result = run(input)
        val total = result.days.last().expectedBalance.pence - result.poolBalanceNow.pence
        assertNear(-40L * 100 * 10, total, 60, "ten weeks of £40 groceries")
        val onCurrent = learnedOn(result, current).toDouble()
        val onSavings = learnedOn(result, savings).toDouble()
        assertEquals(total.toDouble(), onCurrent + onSavings, 0.0)
        assertEquals(0.75, onCurrent / total, 0.005)
        assertEquals(0.25, onSavings / total, 0.005)
        assertEquals(0L, learnedOn(result, outside))
        assertPoolInvariant(result)
    }

    @Test
    fun eachCategoryUsesItsOwnAccountShares() {
        // Groceries only ever on Current, eating out only ever on Savings.
        val history = weekly(current, TestData.GROCERIES, pounds(-40, -40, -40, -40, -40, -40, -40, -40)) +
            weekly(savings, TestData.EATING_OUT, pounds(-25, -25, -25, -25, -25, -25, -25, -25))
        val input = TestData.input(
            today, transactions = history, settings = TestData.settings(historyStartDate = today.minusWeeks(8), forecastHorizonDays = 63),
        )
        val result = run(input)
        val groceriesWeekly = result.categoryEstimates.first { it.categoryId == TestData.GROCERIES && it.regime == Regime.HOLIDAY }.weeklyAmount
        val eatingWeekly = result.categoryEstimates.first { it.categoryId == TestData.EATING_OUT && it.regime == Regime.HOLIDAY }.weeklyAmount
        assertEquals(Money.pounds(-40), groceriesWeekly)
        assertEquals(Money.pounds(-25), eatingWeekly)
        // 63 days = nine weeks: each account carries its own category's spending and nothing else.
        assertNear(-40L * 100 * 9, learnedOn(result, current), 45, "Current carries the groceries")
        assertNear(-25L * 100 * 9, learnedOn(result, savings), 45, "Savings carries the eating out")
        assertPoolInvariant(result)
    }

    @Test
    fun aReconciliationCountsTowardsItsAccountsShare() {
        // Groceries: £20/week of ordinary spending on Current. Untracked: a -£16 balance-update
        // gap every week on Savings (spread over its week as statistics, never the ledger), so
        // Untracked's whole share belongs to Savings.
        val normal = weekly(current, TestData.GROCERIES, pounds(-20, -20, -20, -20, -20, -20, -20, -20))
        val gaps = (1..8).map { w ->
            val sunday = today.minusWeeks(w.toLong()).plusDays(6)
            Transaction(
                id = 900L + w, accountId = savings, amount = Money.pounds(-16), date = sunday,
                categoryId = TestData.UNTRACKED, kind = TxKind.RECONCILIATION,
            )
        }
        val result = run(
            TestData.input(
                today, transactions = normal + gaps, settings = TestData.settings(historyStartDate = today.minusWeeks(8), forecastHorizonDays = 28),
            ),
        )
        val untrackedWeekly = result.categoryEstimates.first { it.categoryId == TestData.UNTRACKED && it.regime == Regime.HOLIDAY }.weeklyAmount
        val groceriesWeekly = result.categoryEstimates.first { it.categoryId == TestData.GROCERIES && it.regime == Regime.HOLIDAY }.weeklyAmount
        assertEquals(Money.pounds(-20), groceriesWeekly)
        assertEquals(Money.pounds(-16), untrackedWeekly)
        assertNear(-20L * 100 * 4, learnedOn(result, current), 20, "Current carries only the groceries")
        assertNear(-16L * 100 * 4, learnedOn(result, savings), 20, "Savings carries only the untracked gap")
        assertPoolInvariant(result)
    }

    @Test
    fun aCategoryWithNoHistoryGoesToTheDefaultAccount() {
        // No transactions at all, so the only learned flow comes from the user's prediction.
        val prediction = Prediction(
            id = 1, categoryId = TestData.GROCERIES, regime = Regime.HOLIDAY, mode = PredictionMode.REPLACE, weeklyAmount = Money.pounds(70),
        )
        fun accountsGetting(defaultAccountId: Long?): Map<Long, Long> {
            val settings = BudgetSettings(defaultAccountId = defaultAccountId, buffer = Money.ZERO, simulations = 50, forecastHorizonDays = 28)
            val input = TestData.input(today, predictions = listOf(prediction), settings = settings)
            val result = run(input)
            assertPoolInvariant(result)
            assertTrue(result.days.last().expectedBalance.pence < 0, "the prediction should be forecast as spending")
            return result.accountForecasts.associate { it.accountId to learnedOn(result, it.accountId) }
        }

        val toSavings = accountsGetting(savings)
        assertEquals(0L, toSavings.getValue(current))
        assertNear(-70L * 100 * 4, toSavings.getValue(savings), 10)
        assertEquals(0L, toSavings.getValue(outside))

        // No default configured: the smallest pool id (Current).
        val toSmallest = accountsGetting(null)
        assertNear(-70L * 100 * 4, toSmallest.getValue(current), 10)
        assertEquals(0L, toSmallest.getValue(savings))

        // A default that is outside the pool is ignored.
        val toSmallestAgain = accountsGetting(outside)
        assertNear(-70L * 100 * 4, toSmallestAgain.getValue(current), 10)
        assertEquals(0L, toSmallestAgain.getValue(outside))
    }

    @Test
    fun historyOnlyOutsideThePoolIsNotUsedForTheSplit() {
        // Spending on the non-pool account is not learned at all, so with a prediction only the
        // default account sees any learned flow.
        val history = weekly(outside, TestData.GROCERIES, pounds(-30, -30, -30, -30, -30, -30, -30, -30))
        val prediction = Prediction(
            id = 1, categoryId = TestData.GROCERIES, regime = Regime.HOLIDAY, mode = PredictionMode.REPLACE, weeklyAmount = Money.pounds(35),
        )
        val result = run(
            TestData.input(
                today, transactions = history, predictions = listOf(prediction),
                settings = TestData.settings(historyStartDate = today.minusWeeks(8), forecastHorizonDays = 14),
            ),
        )
        assertNear(-35L * 100 * 2, learnedOn(result, current), 10)
        assertEquals(0L, learnedOn(result, outside))
    }

    @Test
    fun nonPoolAccountsOnlyGetTheirOwnCommittedFlows() {
        val history = weekly(current, TestData.GROCERIES, pounds(-40, -40, -40, -40, -40, -40, -40, -40))
        val rule = RecurringRule(
            id = 1, label = "Pocket money", accountId = outside, categoryId = null, amount = Money.pounds(5),
            frequency = Frequency.WEEKLY, startDate = today.plusDays(2), postingMode = PostingMode.AUTO,
        )
        val bill = ScheduledItem(
            id = 1, label = "Gift", accountId = outside, categoryId = null, amount = Money.pounds(-20),
            date = today.plusDays(5), postingMode = PostingMode.AUTO,
        )
        val fund = SinkingFund(id = 1, name = "Trip", target = Money.pounds(90), startDate = today.minusDays(3), targetDate = today.plusDays(12))
        val txs = history + opening(current, Money.pounds(2000)) + opening(outside, Money.pounds(15))
        val input = TestData.input(
            today, transactions = txs, recurringRules = listOf(rule), scheduledItems = listOf(bill), sinkingFunds = listOf(fund),
            settings = TestData.settings(historyStartDate = today.minusWeeks(8), forecastHorizonDays = 30, simulations = 80),
            runMonteCarlo = true,
        )
        val result = run(input)
        val forecast = result.account(outside)
        for (offset in 0..30) {
            val pocket = if (offset < 2) 0L else 5L * ((offset - 2) / 7 + 1)
            val gift = if (offset >= 5) -20L else 0L
            val expected = Money.pounds(15 + pocket + gift)
            assertEquals(expected, forecast.on(offset).expectedBalance, "offset $offset")
            // The account has no uncertainty of its own: the band collapses onto the expectation.
            assertEquals(expected, forecast.on(offset).p10)
            assertEquals(expected, forecast.on(offset).p50)
            assertEquals(expected, forecast.on(offset).p90)
        }
        // The fund's target (a pool flow) went to the default account, Current, not to the non-pool
        // account: Current's step on the target date differs from the same weekday a week later
        // (identical learned spending, no other flow) by exactly the £90 target.
        val cur = result.account(current)
        fun step(offset: Int) = cur.on(offset).expectedBalance.pence - cur.on(offset - 1).expectedBalance.pence
        assertEquals(-9000L, step(12) - step(19))
        assertPoolInvariant(result)
    }

    @Test
    fun fundTargetsAndAccountlessScenarioItemsGoToTheDefaultAccount() {
        val fund = SinkingFund(id = 1, name = "Trip", target = Money.pounds(90), startDate = today.minusDays(3), targetDate = today.plusDays(4))
        val scenario = Scenario(
            id = 1, name = "What if",
            items = listOf(
                // No account: a hypothetical pool flow, attributed to the default account.
                ScenarioItem(id = 1, scenarioId = 1, type = ScenarioItemType.ONE_OFF, label = "Gadget", amount = Money.pounds(-10), date = today.plusDays(2)),
                // A named non-pool account: not a pool flow, but it does move that account.
                ScenarioItem(id = 2, scenarioId = 1, type = ScenarioItemType.ONE_OFF, label = "Cash gift", accountId = outside, amount = Money.pounds(5), date = today.plusDays(3)),
            ),
        )
        val txs = listOf(opening(current, Money.pounds(500)), opening(savings, Money.pounds(500)))
        val base = TestData.input(
            today, transactions = txs, sinkingFunds = listOf(fund), scenario = scenario, settings = TestData.settings(forecastHorizonDays = 6),
        )
        val toCurrent = run(base)
        assertEquals(Money.pounds(500), toCurrent.account(current).on(1).expectedBalance)
        assertEquals(Money.pounds(490), toCurrent.account(current).on(2).expectedBalance)
        assertEquals(Money.pounds(400), toCurrent.account(current).on(4).expectedBalance)
        assertEquals(Money.pounds(500), toCurrent.account(savings).on(4).expectedBalance)
        assertEquals(Money.pounds(5), toCurrent.account(outside).on(4).expectedBalance)
        assertEquals(Money.pounds(900), toCurrent.days.last().expectedBalance)

        val configured = run(base.copy(settings = base.settings.copy(defaultAccountId = savings)))
        assertEquals(Money.pounds(500), configured.account(current).on(4).expectedBalance)
        assertEquals(Money.pounds(400), configured.account(savings).on(4).expectedBalance)
        assertPoolInvariant(toCurrent)
        assertPoolInvariant(configured)
    }

    // --- default account fallbacks --------------------------------------------------------------

    private fun acc(id: Long, pool: Boolean = true, archived: Boolean = false) =
        Account(id = id, name = "A$id", type = AccountType.CURRENT, includeInSafeToSpend = pool, archived = archived)

    private fun defaultOf(accounts: List<Account>, configured: Long?) =
        AccountSplit.defaultAccountId(accounts, BudgetSettings(defaultAccountId = configured))

    @Test
    fun defaultAccountIsTheConfiguredOneWhenItIsAValidPoolAccount() {
        assertEquals(5L, defaultOf(listOf(acc(2), acc(5), acc(9)), 5))
    }

    @Test
    fun defaultAccountFallsBackToTheSmallestPoolIdWhenTheConfiguredOneIsArchived() {
        assertEquals(2L, defaultOf(listOf(acc(9), acc(5, archived = true), acc(2)), 5))
    }

    @Test
    fun defaultAccountFallsBackToTheSmallestPoolIdWhenTheConfiguredOneIsOutsideThePool() {
        assertEquals(2L, defaultOf(listOf(acc(9), acc(5, pool = false), acc(2)), 5))
    }

    @Test
    fun defaultAccountFallsBackToTheSmallestPoolIdWhenTheConfiguredOneIsMissing() {
        assertEquals(2L, defaultOf(listOf(acc(9), acc(2)), 77))
    }

    @Test
    fun defaultAccountIsTheSmallestPoolIdWhenNoneIsConfigured() {
        assertEquals(2L, defaultOf(listOf(acc(9), acc(4, pool = false), acc(2), acc(1, archived = true)), null))
    }

    @Test
    fun defaultAccountIsTheSmallestNonArchivedIdWhenThereIsNoUsablePoolAccount() {
        assertEquals(3L, defaultOf(listOf(acc(8, pool = false), acc(3, pool = false), acc(1, archived = true)), null))
        assertEquals(3L, defaultOf(listOf(acc(8, pool = false), acc(3, pool = false), acc(1, pool = true, archived = true)), 8))
    }

    @Test
    fun defaultAccountIsNullWhenEveryAccountIsArchivedOrThereAreNone() {
        assertNull(defaultOf(emptyList(), null))
        assertNull(defaultOf(listOf(acc(1, archived = true), acc(2, pool = false, archived = true)), 1))
    }

    // --- largest remainder ---------------------------------------------------------------------

    private fun split(value: Long, shares: DoubleArray): LongArray {
        val out = LongArray(shares.size)
        AccountSplit.splitLargestRemainder(value, shares, out, DoubleArray(shares.size))
        return out
    }

    @Test
    fun largestRemainderSplitsKnownCasesExactly() {
        val thirds = doubleArrayOf(1.0 / 3, 1.0 / 3, 1.0 / 3)
        assertEquals(listOf(1L, 0L, 0L), split(1, thirds).toList())
        assertEquals(listOf(34L, 33L, 33L), split(100, thirds).toList())
        // -33.33 each: floors are -34 (sum -102), the two leftover pence go to the first two.
        assertEquals(listOf(-33L, -33L, -34L), split(-100, thirds).toList())
        assertEquals(listOf(75L, 25L), split(100, doubleArrayOf(0.75, 0.25)).toList())
        assertEquals(listOf(0L, 0L), split(0, doubleArrayOf(0.5, 0.5)).toList())
        assertEquals(listOf(42L), split(42, doubleArrayOf(1.0)).toList())
        assertEquals(listOf(0L, 7L, 0L), split(7, doubleArrayOf(0.0, 1.0, 0.0)).toList())
    }

    @Test
    fun largestRemainderGivesTheLeftoverPenceToTheLargestRemaindersTiesToTheLowestIndex() {
        // 10 pence over 0.25/0.25/0.5 -> 2.5, 2.5, 5.0: the single leftover penny goes to index 0.
        assertEquals(listOf(3L, 2L, 5L), split(10, doubleArrayOf(0.25, 0.25, 0.5)).toList())
        // 7 over 0.4/0.35/0.25 -> 2.8, 2.45, 1.75: floors 2,2,1 (5), leftovers to .8 then .75.
        assertEquals(listOf(3L, 2L, 2L), split(7, doubleArrayOf(0.4, 0.35, 0.25)).toList())
    }

    @Test
    fun largestRemainderAlwaysSumsExactlyAndNeverAllocatesToZeroShareAccounts() {
        val random = Random(20240603)
        repeat(20_000) { iteration ->
            val n = 1 + random.nextInt(8)
            val weights = LongArray(n) { if (random.nextInt(3) == 0) 0L else 1L + random.nextLong(1_000_000) }
            if (weights.all { it == 0L }) weights[random.nextInt(n)] = 1L + random.nextLong(1000)
            val total = weights.sum().toDouble()
            val shares = DoubleArray(n) { weights[it] / total }
            val value = when (random.nextInt(4)) {
                0 -> random.nextLong(-50, 50)
                1 -> random.nextLong(-10_000, 10_000)
                2 -> random.nextLong(-100_000_000, 100_000_000)
                else -> random.nextLong(-2_000_000_000L, 2_000_000_000L)
            }
            val parts = split(value, shares)
            val context = "iteration $iteration: value=$value shares=${shares.toList()} parts=${parts.toList()}"
            assertEquals(value, parts.sum(), context)
            for (j in 0 until n) {
                if (weights[j] == 0L) assertEquals(0L, parts[j], "zero-share account $j was allocated: $context")
                // Each part is within one penny of its exact proportion.
                assertTrue(Math.abs(parts[j] - value * shares[j]) < 1.0 + 1e-6, "part $j too far from its share: $context")
            }
        }
    }

    // --- percentiles, determinism, the exact invariant ------------------------------------------

    private fun busyInput(seed: Long = 7L, simulations: Int = 150): ForecastInput {
        val history = weekly(current, TestData.GROCERIES, pounds(-50, -30, -60, -20, -45, -55, -25, -40)) +
            weekly(savings, TestData.GROCERIES, pounds(-10, -15, -5, -20, -10, -12, -8, -9)) +
            weekly(current, TestData.EATING_OUT, pounds(-15, -25, -10, -30, -20, -15, -5, -35)) +
            weekly(savings, TestData.WAGES, pounds(80, 0, 120, 60, 90, 0, 100, 70)) +
            weekly(outside, TestData.GROCERIES, pounds(-99, -99, -99, -99, -99, -99, -99, -99))
        val accounts = accounts4()
        val txs = history + listOf(
            opening(current, Money.pounds(700)), opening(savings, Money.pounds(300)),
            opening(outside, Money.pounds(60)), opening(4, Money.pounds(-120)),
        )
        val rent = RecurringRule(
            id = 1, label = "Rent", accountId = current, categoryId = TestData.RENT, amount = Money.pounds(-350),
            frequency = Frequency.MONTHLY, startDate = today.plusDays(9), postingMode = PostingMode.AUTO,
        )
        val topUp = RecurringRule(
            id = 2, label = "Top up", accountId = savings, categoryId = null, amount = Money.pounds(45),
            frequency = Frequency.WEEKLY, interval = 2, startDate = today.plusDays(3), postingMode = PostingMode.AUTO, toAccountId = current,
        )
        val cardPay = RecurringRule(
            id = 3, label = "Card payment", accountId = current, categoryId = null, amount = Money.pounds(30),
            frequency = Frequency.MONTHLY, startDate = today.plusDays(14), postingMode = PostingMode.AUTO, toAccountId = 4,
        )
        val fund = SinkingFund(id = 1, name = "Trip", target = Money.pounds(240), startDate = today.minusDays(5), targetDate = today.plusDays(50))
        return TestData.input(
            today, accounts = accounts, transactions = txs, recurringRules = listOf(rent, topUp, cardPay), sinkingFunds = listOf(fund),
            settings = TestData.settings(historyStartDate = today.minusWeeks(8), simulations = simulations, forecastHorizonDays = 90),
            runMonteCarlo = true, seed = seed,
        )
    }

    @Test
    fun perAccountPercentilesAreOrdered() {
        val result = run(busyInput())
        assertEquals(4, result.accountForecasts.size)
        for (forecast in result.accountForecasts) {
            for (day in forecast.days) {
                val p10 = assertNotNull(day.p10)
                val p50 = assertNotNull(day.p50)
                val p90 = assertNotNull(day.p90)
                assertTrue(p10 <= p50 && p50 <= p90, "account ${forecast.accountId} ${day.date}: $p10 $p50 $p90")
            }
        }
    }

    @Test
    fun sameSeedGivesTheSameAccountForecastsAndADifferentSeedCanDiffer() {
        val a = run(busyInput(seed = 11))
        val b = run(busyInput(seed = 11))
        assertEquals(a.accountForecasts, b.accountForecasts)
        assertEquals(a, b)
        val c = run(busyInput(seed = 12))
        assertTrue(
            a.accountForecasts.map { f -> f.days.map { it.p50 } } != c.accountForecasts.map { f -> f.days.map { it.p50 } },
            "a different seed should move at least one percentile path",
        )
        // The expectation does not depend on the seed.
        assertEquals(
            a.accountForecasts.map { f -> f.days.map { it.expectedBalance } },
            c.accountForecasts.map { f -> f.days.map { it.expectedBalance } },
        )
    }

    @Test
    fun poolAccountsAddUpToThePoolToThePennyOnEveryDay() {
        assertPoolInvariant(run(busyInput()))
        for (horizon in listOf(1, 2, 7, 45, 200)) {
            val input = busyInput().copy(runMonteCarlo = false)
            val result = run(input.copy(settings = input.settings.copy(forecastHorizonDays = horizon)))
            assertPoolInvariant(result)
            assertEquals(horizon + 1, result.accountForecasts.first().days.size)
        }
    }

    @Test
    fun poolInvariantHoldsWithScenariosAndScheduledTransfers() {
        val scenario = Scenario(
            id = 1, name = "What if",
            items = listOf(
                ScenarioItem(id = 1, scenarioId = 1, type = ScenarioItemType.ONE_OFF, label = "Gadget", amount = Money.pounds(-50), date = today.plusDays(4)),
                ScenarioItem(
                    id = 2, scenarioId = 1, type = ScenarioItemType.RECURRING, label = "Gym", accountId = savings, amount = Money.pounds(-10),
                    date = today.plusDays(2), frequency = Frequency.WEEKLY,
                ),
                ScenarioItem(id = 3, scenarioId = 1, type = ScenarioItemType.CATEGORY_ADJUST, categoryId = TestData.GROCERIES, percent = -50),
            ),
        )
        val input = busyInput().copy(
            scenario = scenario,
            scheduledItems = listOf(
                transferItem(1, 4, current, 25, 5),
                transferItem(2, outside, savings, 40, -3, PostingMode.CONFIRM),
                ScheduledItem(
                    id = 3, label = "Refund", accountId = savings, categoryId = TestData.WAGES, amount = Money.pounds(33), date = today.plusDays(20),
                    postingMode = PostingMode.CONFIRM, isMajorIncome = true,
                ),
            ),
        )
        assertPoolInvariant(run(input))
    }

    @Test
    fun withASinglePoolAccountItsPercentilesAreThePoolsPercentiles() {
        val accounts = listOf(
            Account(id = 1, name = "Only", type = AccountType.CURRENT),
            Account(id = 3, name = "Cash", type = AccountType.CASH, includeInSafeToSpend = false),
        )
        val history = weekly(1, TestData.GROCERIES, pounds(-50, -30, -60, -20, -45, -55, -25, -40))
        val input = TestData.input(
            today, accounts = accounts, transactions = history + opening(1, Money.pounds(400)),
            settings = TestData.settings(historyStartDate = today.minusWeeks(8), simulations = 120, forecastHorizonDays = 40), runMonteCarlo = true,
        )
        val result = run(input)
        val only = result.account(1)
        assertEquals(result.days.map { it.p10 }, only.days.map { it.p10 })
        assertEquals(result.days.map { it.p50 }, only.days.map { it.p50 })
        assertEquals(result.days.map { it.p90 }, only.days.map { it.p90 })
        assertEquals(result.days.map { it.expectedBalance }, only.days.map { it.expectedBalance })
    }

    @Test
    fun anAccountWithNoLearnedShareHasAConstantOffsetToThePoolPercentiles() {
        // All spending history is on Current, so Savings' balance is deterministic and the pool
        // band is Current's band shifted by Savings' expected balance.
        val history = weekly(current, TestData.GROCERIES, pounds(-50, -30, -60, -20, -45, -55, -25, -40))
        val txs = history + opening(current, Money.pounds(600)) + opening(savings, Money.pounds(250))
        val interest = RecurringRule(
            id = 1, label = "Interest", accountId = savings, categoryId = null, amount = Money.pounds(3),
            frequency = Frequency.WEEKLY, startDate = today.plusDays(1), postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(
            today, transactions = txs, recurringRules = listOf(interest),
            settings = TestData.settings(historyStartDate = today.minusWeeks(8), simulations = 150, forecastHorizonDays = 60), runMonteCarlo = true,
        )
        val result = run(input)
        val cur = result.account(current)
        val sav = result.account(savings)
        for (i in result.days.indices) {
            val saved = sav.days[i]
            assertEquals(saved.expectedBalance, saved.p10, "Savings is deterministic on day $i")
            assertEquals(saved.expectedBalance, saved.p90, "Savings is deterministic on day $i")
            assertEquals(result.days[i].p10, cur.days[i].p10!! + saved.expectedBalance, "p10 on day $i")
            assertEquals(result.days[i].p50, cur.days[i].p50!! + saved.expectedBalance, "p50 on day $i")
            assertEquals(result.days[i].p90, cur.days[i].p90!! + saved.expectedBalance, "p90 on day $i")
        }
        assertTrue(cur.days.last().p10!! < cur.days.last().p90!!, "Current should have a real band")
    }

    @Test
    fun horizonDayCountMatchesTheDaysList() {
        val result = run(busyInput().copy(runMonteCarlo = false))
        val expected = ChronoUnit.DAYS.between(result.today, result.forecastEnd).toInt() + 1
        assertEquals(expected, result.days.size)
        for (forecast in result.accountForecasts) assertEquals(expected, forecast.days.size)
    }

    @Test
    fun noPoolAccountsMeansEveryAccountIsDeterministic() {
        val accounts = listOf(
            Account(id = 1, name = "Cash", type = AccountType.CASH, includeInSafeToSpend = false),
            Account(id = 2, name = "Card", type = AccountType.CREDIT_CARD, includeInSafeToSpend = false),
        )
        val bill = ScheduledItem(
            id = 1, label = "Bill", accountId = 2, categoryId = null, amount = Money.pounds(-12), date = today.plusDays(3),
            postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(
            today, accounts = accounts, transactions = listOf(opening(1, Money.pounds(9)), opening(2, Money.pounds(-40))),
            scheduledItems = listOf(bill), settings = TestData.settings(forecastHorizonDays = 6, simulations = 60), runMonteCarlo = true,
        )
        val result = run(input)
        assertEquals(Money.ZERO, result.poolBalanceNow)
        assertEquals(Money.pounds(9), result.account(1).days.last().expectedBalance)
        assertEquals(Money.pounds(-52), result.account(2).days.last().expectedBalance)
        assertEquals(Money.pounds(-52), result.account(2).days.last().p50)
        assertTrue(result.accountForecasts.none { it.inPool })
    }
}
