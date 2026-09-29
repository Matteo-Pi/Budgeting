package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.forecast.CommittedFlow
import io.github.matteopi.budgeting.core.forecast.DayForecast
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.RiskSummary
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ForecastChartMappingTest {

    private val today = LocalDate.of(2026, 9, 28)

    private fun day(date: LocalDate, p50Raw: Long, p10Raw: Long? = null, p90Raw: Long? = null, tier1Raw: Long = p50Raw): DayForecast =
        DayForecast(
            date = date,
            regime = Regime.TERM,
            committedNet = Money.ZERO,
            expectedLearned = Money.ZERO,
            tier1Balance = Money(tier1Raw),
            expectedBalance = Money(p50Raw),
            p10 = p10Raw?.let { Money(it) },
            p50 = Money(p50Raw),
            p90 = p90Raw?.let { Money(it) },
            reserved = Money.ZERO,
        )

    private fun result(days: List<DayForecast>, poolOverdraft: Money = Money.ZERO, risk: RiskSummary? = null): ForecastResult =
        ForecastResult(
            today = today,
            forecastEnd = days.last().date,
            poolBalanceNow = days.first().p50 ?: Money.ZERO,
            poolOverdraft = poolOverdraft,
            floor = if (poolOverdraft.isZero) Money.ZERO else -poolOverdraft,
            days = days,
            categoryEstimates = emptyList(),
            needsPrediction = emptyList(),
            safeToSpend = SafeToSpend(
                weekly = Money.pounds(50),
                perDay = Money.pounds(7),
                horizonEnd = days.last().date,
                nextMajorIncome = null,
                daysUntilNextMajorIncome = null,
                bindingDate = null,
                bindingReason = null,
                shortfall = Money.ZERO,
                predictedWeeklySpending = Money.pounds(40),
            ),
            risk = risk,
            budgetPeriods = emptyList(),
            fundReservations = emptyList(),
            committedFlows = emptyList<CommittedFlow>(),
            tier1WeeklyByCategory = emptyMap(),
            historyStart = null,
            completeWeeksOfHistory = 0,
        )

    @Test
    fun toChartDays_accountBalanceZeroPointIsUnchanged() {
        val r = result(listOf(day(today, p50Raw = 10_000, p10Raw = 8_000, p90Raw = 12_000)))
        val chart = ForecastChartMapping.toChartDays(r, ZeroPoint.ACCOUNT_BALANCE)
        assertEquals(Money(10_000), chart[0].p50)
        assertEquals(Money(8_000), chart[0].p10)
        assertEquals(Money(12_000), chart[0].p90)
    }

    @Test
    fun toChartDays_overdraftZeroPointAddsTheOverdraftLimit() {
        val overdraft = Money.pounds(200)
        val r = result(
            listOf(day(today, p50Raw = -5_000, p10Raw = -10_000, p90Raw = 0)),
            poolOverdraft = overdraft,
        )
        val chart = ForecastChartMapping.toChartDays(r, ZeroPoint.OVERDRAFT_LIMIT)
        // Display = raw + overdraft.
        assertEquals(Money(-5_000) + overdraft, chart[0].p50)
        assertEquals(Money(-10_000) + overdraft, chart[0].p10)
        assertEquals(Money(0) + overdraft, chart[0].p90)
    }

    @Test
    fun toChartDays_fallsBackToExpectedBalanceWhenMonteCarloWasSkipped() {
        val r = result(listOf(day(today, p50Raw = 4_200, p10Raw = null, p90Raw = null)))
        val chart = ForecastChartMapping.toChartDays(r, ZeroPoint.ACCOUNT_BALANCE)
        assertEquals(Money(4_200), chart[0].p50)
        assertEquals(null, chart[0].p10)
        assertEquals(null, chart[0].p90)
    }

    @Test
    fun horizonEndDate_capsAtForecastEnd() {
        val end = today.plusDays(10)
        val r = result(listOf(day(today, 0), day(end, 0)))
        assertEquals(end, ForecastChartMapping.horizonEndDate(ForecastHorizonOption.WEEKS_26, r))
    }

    @Test
    fun horizonEndDate_untilNextIncomeUsesSafeToSpendHorizon() {
        val end = today.plusDays(90)
        val r = result(listOf(day(today, 0), day(end, 0)))
        assertEquals(end, ForecastChartMapping.horizonEndDate(ForecastHorizonOption.UNTIL_NEXT_INCOME, r))
    }

    @Test
    fun horizonEndDate_untilNextIncomeStopsAtTheIncomeWhenThereIsOne() {
        val end = today.plusDays(90)
        val incomeDate = today.plusDays(21)
        val base = result(listOf(day(today, 0), day(end, 0)))
        val income = CommittedFlow(
            date = incomeDate,
            amount = Money.pounds(1_000),
            label = "Student loan",
            source = io.github.matteopi.budgeting.core.forecast.FlowSource.SCHEDULED,
            sourceId = 1,
            categoryId = null,
            accountId = null,
            isMajorIncome = true,
        )
        val r = base.copy(safeToSpend = base.safeToSpend.copy(nextMajorIncome = income))
        assertEquals(incomeDate, ForecastChartMapping.horizonEndDate(ForecastHorizonOption.UNTIL_NEXT_INCOME, r))
    }

    @Test
    fun riskSentence_belowFivePercentIsReassuring() {
        val risk = RiskSummary(
            probBelowFloor = 0.04,
            probBeyondOverdraft = 0.0,
            firstLikelyBreach = null,
            endBalanceP10 = Money.ZERO,
            endBalanceP50 = Money.ZERO,
            endBalanceP90 = Money.ZERO,
        )
        assertEquals("Very unlikely to go below £0.", ForecastChartMapping.riskSentence(risk, today))
    }

    @Test
    fun riskSentence_includesPercentAndDate() {
        val risk = RiskSummary(
            probBelowFloor = 0.183,
            probBeyondOverdraft = 0.0,
            firstLikelyBreach = null,
            endBalanceP10 = Money.ZERO,
            endBalanceP50 = Money.ZERO,
            endBalanceP90 = Money.ZERO,
        )
        assertEquals(
            "18% chance of going below £0 before 12 Jan.",
            ForecastChartMapping.riskSentence(risk, LocalDate.of(2027, 1, 12)),
        )
    }

    @Test
    fun riskSentence_nullRiskIsHandledGracefully() {
        assertEquals("Working out the risk of going below £0…", ForecastChartMapping.riskSentence(null, today))
    }
}
