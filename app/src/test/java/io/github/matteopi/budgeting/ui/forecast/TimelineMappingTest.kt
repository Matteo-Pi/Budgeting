package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.forecast.CommittedFlow
import io.github.matteopi.budgeting.core.forecast.DayForecast
import io.github.matteopi.budgeting.core.forecast.FlowSource
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelineMappingTest {

    private val today = LocalDate.of(2026, 9, 28)

    /** [daysCount] days after today, each with a flat -£5/day expected spend and a P10 of -£10. */
    private fun buildResult(
        daysCount: Int,
        committedFlows: List<CommittedFlow> = emptyList(),
        regimeOf: (LocalDate) -> Regime = { Regime.TERM },
    ): ForecastResult {
        val days = (0..daysCount).map { i ->
            val date = today.plusDays(i.toLong())
            DayForecast(
                date = date,
                regime = regimeOf(date),
                committedNet = Money.ZERO,
                expectedLearned = Money(-500),
                tier1Balance = Money.ZERO,
                expectedBalance = Money.ZERO,
                p10 = Money(-1_000),
                p50 = Money.ZERO,
                p90 = Money(1_000),
                reserved = Money.ZERO,
            )
        }
        return ForecastResult(
            today = today,
            forecastEnd = days.last().date,
            poolBalanceNow = Money.ZERO,
            poolOverdraft = Money.ZERO,
            floor = Money.ZERO,
            days = days,
            categoryEstimates = emptyList(),
            needsPrediction = emptyList(),
            safeToSpend = SafeToSpend(
                weekly = Money.ZERO,
                perDay = Money.ZERO,
                horizonEnd = days.last().date,
                nextMajorIncome = null,
                daysUntilNextMajorIncome = null,
                bindingDate = null,
                bindingReason = null,
                shortfall = Money.ZERO,
                predictedWeeklySpending = Money.ZERO,
            ),
            risk = null,
            budgetPeriods = emptyList(),
            fundReservations = emptyList(),
            committedFlows = committedFlows,
            tier1WeeklyByCategory = emptyMap(),
            historyStart = null,
            completeWeeksOfHistory = 0,
        )
    }

    @Test
    fun buildWeeks_everyWeekStartsOnAMondayAndIsContiguous() {
        val result = buildResult(daysCount = 30)
        val weeks = TimelineMapping.buildWeeks(result, terms = emptyList(), zeroPoint = ZeroPoint.ACCOUNT_BALANCE)

        assertTrue(weeks.isNotEmpty())
        weeks.forEach { assertEquals(DayOfWeek.MONDAY, it.weekStart.dayOfWeek) }
        for (i in 1 until weeks.size) {
            assertEquals(weeks[i - 1].weekStart.plusDays(7), weeks[i].weekStart)
        }
    }

    @Test
    fun buildWeeks_groupCountMatchesDistinctWeekStartsInRange() {
        val result = buildResult(daysCount = 30)
        val weeks = TimelineMapping.buildWeeks(result, terms = emptyList(), zeroPoint = ZeroPoint.ACCOUNT_BALANCE)

        val firstForecastDay = today.plusDays(1)
        val expectedWeekStarts = (0..29).map { Regimes.weekStart(firstForecastDay.plusDays(it.toLong())) }.distinct()
        assertEquals(expectedWeekStarts.size, weeks.size)
        assertEquals(expectedWeekStarts.sorted(), weeks.map { it.weekStart })
    }

    @Test
    fun buildWeeks_sumsExpectedLearnedOverAFullWeek() {
        val result = buildResult(daysCount = 30)
        val weeks = TimelineMapping.buildWeeks(result, terms = emptyList(), zeroPoint = ZeroPoint.ACCOUNT_BALANCE)
        val firstForecastDay = today.plusDays(1)
        val lastForecastDay = result.days.last().date

        val fullWeek = weeks.firstOrNull { week ->
            !week.weekStart.isBefore(firstForecastDay) && !week.weekStart.plusDays(6).isAfter(lastForecastDay)
        }
        assertNotNull(fullWeek)
        assertEquals(Money(-3_500), fullWeek.expectedLearned)
    }

    @Test
    fun buildWeeks_flagsWeeksWhoseP10IsBelowTheFloor() {
        val result = buildResult(daysCount = 30)
        val weeks = TimelineMapping.buildWeeks(result, terms = emptyList(), zeroPoint = ZeroPoint.ACCOUNT_BALANCE)
        // Every day's p10 is -£10 and the floor is £0, so every week should be flagged.
        weeks.forEach { assertTrue(it.belowFloor) }
    }

    @Test
    fun buildWeeks_namesTheDominantTermForATermWeek() {
        val terms = listOf(TermPeriod(id = 1, name = "Autumn", startDate = today, endDate = today.plusDays(60)))
        val result = buildResult(daysCount = 10, regimeOf = { Regime.TERM })
        val weeks = TimelineMapping.buildWeeks(result, terms = terms, zeroPoint = ZeroPoint.ACCOUNT_BALANCE)

        weeks.forEach { week ->
            assertEquals(Regime.TERM, week.regime)
            assertEquals("Autumn", week.termName)
        }
    }

    @Test
    fun buildWeeks_holidayWeeksHaveNoTermName() {
        val result = buildResult(daysCount = 10, regimeOf = { Regime.HOLIDAY })
        val weeks = TimelineMapping.buildWeeks(result, terms = emptyList(), zeroPoint = ZeroPoint.ACCOUNT_BALANCE)

        weeks.forEach { week ->
            assertEquals(Regime.HOLIDAY, week.regime)
            assertEquals(null, week.termName)
        }
    }

    @Test
    fun buildWeeks_includesOnlyFlowsWithinTheWeek() {
        val firstForecastDay = today.plusDays(1)
        val flowInWeek = CommittedFlow(
            date = firstForecastDay,
            amount = Money(-2_000),
            label = "Rent",
            source = FlowSource.RECURRING,
            sourceId = 1,
            categoryId = null,
            accountId = 1,
            isMajorIncome = false,
        )
        val flowFarAway = flowInWeek.copy(date = firstForecastDay.plusDays(25), label = "Far away")
        val result = buildResult(daysCount = 30, committedFlows = listOf(flowInWeek, flowFarAway))
        val weeks = TimelineMapping.buildWeeks(result, terms = emptyList(), zeroPoint = ZeroPoint.ACCOUNT_BALANCE)

        val firstWeek = weeks.first()
        assertTrue(firstWeek.flows.any { it.label == "Rent" })
        assertTrue(firstWeek.flows.none { it.label == "Far away" })
    }
}
