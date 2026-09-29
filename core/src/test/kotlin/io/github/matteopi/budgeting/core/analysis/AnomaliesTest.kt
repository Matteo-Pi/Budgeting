package io.github.matteopi.budgeting.core.analysis

import io.github.matteopi.budgeting.core.forecast.CategoryEstimate
import io.github.matteopi.budgeting.core.forecast.DataSufficiency
import io.github.matteopi.budgeting.core.forecast.EstimateSource
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnomaliesTest {

    private val account = Account(id = 1, name = "Current", type = AccountType.CURRENT)
    private val eatingOut = Category(id = 10, name = "Eating out", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
    private val groceries = Category(id = 11, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)

    // A Monday, so "this week" (Monday..today) covers exactly one day and "last week" is a full week.
    private val today: LocalDate = LocalDate.of(2026, 9, 28).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
    private val lastWeekStart = today.minusDays(7)

    private fun estimate(category: Category, weeklyPounds: Long, source: EstimateSource = EstimateSource.DATA) =
        CategoryEstimate(
            categoryId = category.id,
            regime = Regime.HOLIDAY, // no term periods supplied, so every day defaults to HOLIDAY
            weeklyAmount = Money.pounds(weeklyPounds),
            automaticWeeklyAmount = Money.pounds(weeklyPounds),
            source = source,
            weeksOfData = 8,
            sufficiency = DataSufficiency.TRUSTED,
            dayOfWeekShares = uniformDayShares,
        )

    private fun tx(categoryId: Long, date: LocalDate, pounds: Long) = Transaction(
        accountId = account.id, amount = Money.pounds(pounds), date = date, categoryId = categoryId,
    )

    /** The account's opening balance, a month before last week: the user has been tracking since then. */
    private val opening = Transaction(
        accountId = account.id, amount = Money.pounds(500), date = lastWeekStart.minusDays(30), categoryId = null,
        kind = TxKind.OPENING_BALANCE,
    )

    private fun input(transactions: List<Transaction>, reconciliations: List<Reconciliation> = emptyList()) = ForecastInput(
        today = today,
        accounts = listOf(account),
        categories = listOf(eatingOut, groceries),
        transactions = transactions,
        recurringRules = emptyList(),
        scheduledItems = emptyList(),
        termPeriods = emptyList(),
        predictions = emptyList(),
        sinkingFunds = emptyList(),
        reconciliations = reconciliations,
        settings = BudgetSettings(),
    )

    @Test
    fun `high spending in a complete last week is flagged, matching the usual weekly figure`() {
        val transactions = listOf(tx(eatingOut.id, lastWeekStart.plusDays(2), -62))
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35)))
        val anomalies = Anomalies.detect(input(transactions), result)

        val hit = anomalies.single { it.kind == AnomalyKind.HIGH_CATEGORY }
        assertEquals(eatingOut.id, hit.categoryId)
        assertEquals(lastWeekStart, hit.weekStart)
        assertEquals(Money.pounds(62), hit.actual)
        assertEquals(Money.pounds(35), hit.expected)
        assertTrue(hit.message.contains("£62"))
        assertTrue(hit.message.contains("£35"))
    }

    @Test
    fun `spending just under the threshold is not flagged`() {
        // 1.5x of 35 = 52.50; 40 is below that.
        val transactions = listOf(tx(eatingOut.id, lastWeekStart.plusDays(2), -40))
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35)))
        val anomalies = Anomalies.detect(input(transactions), result)
        assertTrue(anomalies.none { it.kind == AnomalyKind.HIGH_CATEGORY })
    }

    @Test
    fun `an estimate source of NONE is never flagged even if spending looks high`() {
        val transactions = listOf(tx(eatingOut.id, lastWeekStart.plusDays(2), -100))
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35, source = EstimateSource.NONE)))
        val anomalies = Anomalies.detect(input(transactions), result)
        assertTrue(anomalies.none { it.kind == AnomalyKind.HIGH_CATEGORY })
    }

    @Test
    fun `high total discretionary spending across categories is flagged`() {
        val transactions = listOf(
            tx(eatingOut.id, lastWeekStart.plusDays(1), -30),
            tx(groceries.id, lastWeekStart.plusDays(2), -60),
        )
        val result = testForecastResult(
            today,
            listOf(estimate(eatingOut, -20), estimate(groceries, -30)),
        )
        val anomalies = Anomalies.detect(input(transactions), result)
        val hit = anomalies.single { it.kind == AnomalyKind.HIGH_TOTAL }
        assertEquals(Money.pounds(90), hit.actual)
        assertEquals(Money.pounds(50), hit.expected)
    }

    @Test
    fun `quiet week with no reconciliation is flagged as low logging`() {
        // expected 35, actual well under 40% of that (14), no reconciliation in the last 7 days.
        val transactions = listOf(opening, tx(eatingOut.id, lastWeekStart.plusDays(2), -5))
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35)))
        val anomalies = Anomalies.detect(input(transactions), result)

        val hit = anomalies.single { it.kind == AnomalyKind.LOW_LOGGING }
        assertEquals(lastWeekStart, hit.weekStart)
        assertEquals("Quiet week — did you forget to log something? Update your balances to check.", hit.message)
    }

    @Test
    fun `low logging is not flagged for a week before the user started tracking`() {
        // Set up today: the opening balance is today's, so last week had nothing to log.
        val transactions = listOf(opening.copy(date = today))
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35)))
        val anomalies = Anomalies.detect(input(transactions), result)
        assertTrue(anomalies.none { it.kind == AnomalyKind.LOW_LOGGING })
    }

    @Test
    fun `low logging is not flagged when tracking started part-way through last week`() {
        val transactions = listOf(opening.copy(date = lastWeekStart.plusDays(3)))
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35)))
        assertTrue(Anomalies.detect(input(transactions), result).none { it.kind == AnomalyKind.LOW_LOGGING })
    }

    @Test
    fun `a recent reconciliation suppresses the low logging anomaly`() {
        val transactions = listOf(opening, tx(eatingOut.id, lastWeekStart.plusDays(2), -5))
        val reconciliations = listOf(
            Reconciliation(
                accountId = account.id, date = today.minusDays(1), expected = Money.ZERO, actual = Money.ZERO,
                periodStart = today.minusDays(7),
            ),
        )
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35)))
        val anomalies = Anomalies.detect(input(transactions, reconciliations), result)
        assertTrue(anomalies.none { it.kind == AnomalyKind.LOW_LOGGING })
    }

    @Test
    fun `low logging is not flagged when expected spending is small`() {
        // expected weekly is only £10, below the £30 minimum for LOW_LOGGING.
        val result = testForecastResult(today, listOf(estimate(eatingOut, -10)))
        val anomalies = Anomalies.detect(input(emptyList()), result)
        assertTrue(anomalies.none { it.kind == AnomalyKind.LOW_LOGGING })
    }

    @Test
    fun `TOO_THIN and NONE estimates never surface a category anomaly (NONE is effectively skipped)`() {
        val transactions = listOf(tx(eatingOut.id, lastWeekStart.plusDays(2), -200))
        val result = testForecastResult(
            today,
            listOf(
                CategoryEstimate(
                    categoryId = eatingOut.id, regime = Regime.HOLIDAY, weeklyAmount = Money.ZERO,
                    automaticWeeklyAmount = Money.ZERO, source = EstimateSource.NONE, weeksOfData = 0,
                    sufficiency = DataSufficiency.TOO_THIN, dayOfWeekShares = uniformDayShares,
                ),
            ),
        )
        val anomalies = Anomalies.detect(input(transactions), result)
        assertTrue(anomalies.none { it.kind == AnomalyKind.HIGH_CATEGORY && it.categoryId == eatingOut.id })
    }

    @Test
    fun `partial current week uses the day shares covered so far`() {
        // Only "today" (Monday) is covered so far this week; uniform shares -> expected = 35/7 = 5.
        val transactions = listOf(tx(eatingOut.id, today, -20))
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35)))
        val anomalies = Anomalies.detect(input(transactions), result)

        val hit = anomalies.single { it.kind == AnomalyKind.HIGH_CATEGORY && it.weekStart == today }
        assertEquals(Money.pounds(20), hit.actual)
        assertEquals(Money.pounds(5), hit.expected)
    }

    @Test
    fun `accounts outside the pool are ignored`() {
        val outsidePool = account.copy(id = 2, includeInSafeToSpend = false)
        val transactions = listOf(
            Transaction(accountId = outsidePool.id, amount = Money.pounds(-200), date = lastWeekStart.plusDays(1), categoryId = eatingOut.id),
        )
        val result = testForecastResult(today, listOf(estimate(eatingOut, -35)))
        val anomalies = Anomalies.detect(
            ForecastInput(
                today = today, accounts = listOf(account, outsidePool), categories = listOf(eatingOut),
                transactions = transactions, recurringRules = emptyList(), scheduledItems = emptyList(),
                termPeriods = emptyList(), predictions = emptyList(), sinkingFunds = emptyList(),
                reconciliations = emptyList(), settings = BudgetSettings(),
            ),
            result,
        )
        assertTrue(anomalies.none { it.kind == AnomalyKind.HIGH_CATEGORY })
    }
}
