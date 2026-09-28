package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SinkingFundsTest {
    private val start = LocalDate.of(2024, 1, 1)
    private val target = LocalDate.of(2024, 1, 11) // 10 days after start

    private val fund = SinkingFund(id = 1, name = "Trip", target = Money.pounds(100), startDate = start, targetDate = target)

    @Test
    fun zeroBeforeTheStartDate() {
        assertEquals(Money.ZERO, SinkingFunds.reserved(fund, start.minusDays(1)))
    }

    @Test
    fun rampsLinearlyAndIsFullyReservedTheDayBeforeTarget() {
        assertEquals(Money.pounds(10), SinkingFunds.reserved(fund, start)) // 1/10
        assertEquals(Money.pounds(50), SinkingFunds.reserved(fund, start.plusDays(4))) // 5/10
        assertEquals(Money.pounds(100), SinkingFunds.reserved(fund, target.minusDays(1))) // 10/10
    }

    @Test
    fun zeroFromTheTargetDateOnwards() {
        assertEquals(Money.ZERO, SinkingFunds.reserved(fund, target))
        assertEquals(Money.ZERO, SinkingFunds.reserved(fund, target.plusDays(30)))
    }

    @Test
    fun nonActiveFundsAreNeverReserved() {
        val completed = fund.copy(status = SinkingFundStatus.COMPLETED)
        val cancelled = fund.copy(status = SinkingFundStatus.CANCELLED)
        assertEquals(Money.ZERO, SinkingFunds.reserved(completed, start.plusDays(4)))
        assertEquals(Money.ZERO, SinkingFunds.reserved(cancelled, start.plusDays(4)))
    }

    @Test
    fun degenerateFundWithTargetNotAfterStartIsFullyReservedOnceStarted() {
        val degenerate = fund.copy(startDate = target, targetDate = target)
        assertEquals(Money.ZERO, SinkingFunds.reserved(degenerate, target.minusDays(1)))
        assertEquals(Money.pounds(100), SinkingFunds.reserved(degenerate, target))
        assertEquals(Money.pounds(100), SinkingFunds.reserved(degenerate, target.plusDays(100)))
    }

    @Test
    fun weeklyContributionIsTargetTimesSevenOverTheRampDays() {
        // 100 pounds over 10 days -> 700/10 = 70/week.
        assertEquals(Money.pounds(70), SinkingFunds.weeklyContribution(fund))
    }

    @Test
    fun totalReservedSumsAcrossFunds() {
        val second = SinkingFund(id = 2, name = "Camera", target = Money.pounds(50), startDate = start, targetDate = start.plusDays(5))
        val total = SinkingFunds.totalReserved(listOf(fund, second), start.plusDays(2))
        // fund: 3/10 * 100 = 30; second: 3/5 * 50 = 30.
        assertEquals(Money.pounds(60), total)
    }

    @Test
    fun reservationsListsOnlyActiveFunds() {
        val completed = SinkingFund(id = 2, name = "Done", target = Money.pounds(20), startDate = start, targetDate = start.plusDays(5), status = SinkingFundStatus.COMPLETED)
        val reservations = SinkingFunds.reservations(listOf(fund, completed), start.plusDays(2))
        assertEquals(listOf(1L), reservations.map { it.fundId })
    }
}
