package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Virtual reservations for sinking funds. See `docs/PLAN.md` §5.10. */
object SinkingFunds {
    /**
     * How much of [fund]'s target is reserved as of [date]: 0 before its start date, ramping up
     * linearly to fully reserved the day before its target date, then 0 from the target date
     * (the money is then spent -- see the committed flow in [CommittedFlows]).
     *
     * When `start >= targetDate` there is no ramp period to compute a fraction over. This is a
     * malformed/edge case (a fund whose target date isn't after its start), so it is treated as
     * immediately and fully reserved from its start date onward, rather than dividing by zero.
     */
    fun reserved(fund: SinkingFund, date: LocalDate): Money {
        if (fund.status != SinkingFundStatus.ACTIVE) return Money.ZERO
        val start = fund.startDate
        val target = fund.targetDate
        if (date.isBefore(start)) return Money.ZERO
        if (start.isBefore(target)) {
            if (!date.isBefore(target)) return Money.ZERO
            val totalDays = ChronoUnit.DAYS.between(start, target)
            val elapsedDays = ChronoUnit.DAYS.between(start, date) + 1
            val fraction = minOf(1.0, elapsedDays.toDouble() / totalDays.toDouble())
            return fund.target.scale(fraction)
        }
        return fund.target
    }

    fun totalReserved(funds: List<SinkingFund>, date: LocalDate): Money =
        Money(funds.sumOf { reserved(it, date).pence })

    /**
     * `target × 7 / (targetDate − start)` days. When there is no ramp period (see [reserved]),
     * the fund needs its whole target immediately, so one "week's" contribution is defined as
     * the full target scaled up as if the (non-existent) ramp were a single day.
     */
    fun weeklyContribution(fund: SinkingFund): Money {
        val totalDays = ChronoUnit.DAYS.between(fund.startDate, fund.targetDate)
        val days = if (totalDays > 0) totalDays else 1L
        return Money(fund.target.pence * 7 / days)
    }

    fun reservations(funds: List<SinkingFund>, today: LocalDate): List<FundReservation> =
        funds.filter { it.status == SinkingFundStatus.ACTIVE }.map {
            FundReservation(
                fundId = it.id,
                name = it.name,
                target = it.target,
                targetDate = it.targetDate,
                reservedToday = reserved(it, today),
                weeklyContribution = weeklyContribution(it),
            )
        }
}
