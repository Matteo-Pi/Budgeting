package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate

/**
 * The forecast engine's entry point: pure, no I/O, deterministic for equal input. See
 * `docs/PLAN.md` §5.13.
 */
object ForecastEngine {
    fun run(input: ForecastInput): ForecastResult {
        val today = input.today
        val terms = input.termPeriods
        val forecastEnd = today.plusDays(input.settings.forecastHorizonDays.toLong())

        val poolBalanceNow = Balances.poolBalance(input.accounts, input.transactions, today)
        val poolOverdraft = Balances.poolOverdraft(input.accounts)
        val floor = Balances.floor(input.accounts, input.settings.zeroPoint)

        val committedFlows = CommittedFlows.build(input, forecastEnd)
        val history = HistoryBuilder.build(input)
        val tier1Weekly = Tier1.weeklyEstimates(history, input)
        val tier1DailyTotal = Tier1.dailyTotal(tier1Weekly)
        val tier2 = Tier2.compute(history, input, today, forecastEnd)
        val fundReservations = SinkingFunds.reservations(input.sinkingFunds, today)

        val safeToSpend = SafeToSpendCalculator.compute(
            input, today, forecastEnd, committedFlows, tier2.estimateByKey, tier2.dayOfWeekShares, poolBalanceNow, floor,
        )
        val budgetPeriods = BudgetPeriods.compute(
            input, today, forecastEnd, committedFlows, tier2.estimateByKey, tier2.dayOfWeekShares, poolBalanceNow, floor,
        )

        val learnedCategories = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }
        val committedByDate = committedFlows.groupBy { it.date }

        val forecastDays = ArrayList<LocalDate>()
        run {
            var d = today.plusDays(1)
            while (!d.isAfter(forecastEnd)) {
                forecastDays.add(d)
                d = d.plusDays(1)
            }
        }

        val committedNetByDay = ArrayList<Money>(forecastDays.size)
        val expectedLearnedByDay = ArrayList<Money>(forecastDays.size)
        val tier1BalanceByDay = ArrayList<Money>(forecastDays.size)
        val expectedBalanceByDay = ArrayList<Money>(forecastDays.size)
        val reservedByDay = ArrayList<Money>(forecastDays.size)

        var cumCommitted = 0L
        var cumExpectedLearned = 0L
        var cumTier1 = 0L
        for (d in forecastDays) {
            val committedNet = Money(committedByDate[d].orEmpty().sumOf { it.amount.pence })
            var expectedLearnedPence = 0L
            for (c in learnedCategories) {
                expectedLearnedPence += categoryDayAmount(c, d, terms, tier2.estimateByKey, tier2.dayOfWeekShares).pence
            }
            cumCommitted += committedNet.pence
            cumExpectedLearned += expectedLearnedPence
            cumTier1 += tier1DailyTotal.pence

            committedNetByDay.add(committedNet)
            expectedLearnedByDay.add(Money(expectedLearnedPence))
            tier1BalanceByDay.add(Money(poolBalanceNow.pence + cumCommitted + cumTier1))
            expectedBalanceByDay.add(Money(poolBalanceNow.pence + cumCommitted + cumExpectedLearned))
            reservedByDay.add(SinkingFunds.totalReserved(input.sinkingFunds, d))
        }

        val tier3 = if (input.runMonteCarlo) {
            Tier3.run(
                input = input,
                history = history,
                estimateByKey = tier2.estimateByKey,
                dayOfWeekShares = tier2.dayOfWeekShares,
                forecastDays = forecastDays,
                committedNetByDay = committedNetByDay,
                poolBalanceNow = poolBalanceNow,
                floor = floor,
                poolOverdraft = poolOverdraft,
                horizonEnd = safeToSpend.horizonEnd,
            )
        } else {
            null
        }

        val days = ArrayList<DayForecast>(forecastDays.size + 1)
        days.add(
            DayForecast(
                date = today,
                regime = Regimes.regimeOf(today, terms),
                committedNet = Money.ZERO,
                expectedLearned = Money.ZERO,
                tier1Balance = poolBalanceNow,
                expectedBalance = poolBalanceNow,
                p10 = if (tier3 != null) poolBalanceNow else null,
                p50 = if (tier3 != null) poolBalanceNow else null,
                p90 = if (tier3 != null) poolBalanceNow else null,
                reserved = SinkingFunds.totalReserved(input.sinkingFunds, today),
            ),
        )
        for (i in forecastDays.indices) {
            val d = forecastDays[i]
            days.add(
                DayForecast(
                    date = d,
                    regime = Regimes.regimeOf(d, terms),
                    committedNet = committedNetByDay[i],
                    expectedLearned = expectedLearnedByDay[i],
                    tier1Balance = tier1BalanceByDay[i],
                    expectedBalance = expectedBalanceByDay[i],
                    p10 = tier3?.p10ByDay?.get(i),
                    p50 = tier3?.p50ByDay?.get(i),
                    p90 = tier3?.p90ByDay?.get(i),
                    reserved = reservedByDay[i],
                ),
            )
        }

        return ForecastResult(
            today = today,
            forecastEnd = forecastEnd,
            poolBalanceNow = poolBalanceNow,
            poolOverdraft = poolOverdraft,
            floor = floor,
            days = days,
            categoryEstimates = tier2.estimates,
            needsPrediction = tier2.needsPrediction,
            safeToSpend = safeToSpend,
            risk = tier3?.risk,
            budgetPeriods = budgetPeriods,
            fundReservations = fundReservations,
            committedFlows = committedFlows,
            tier1WeeklyByCategory = tier1Weekly,
            historyStart = history.historyStart,
            completeWeeksOfHistory = history.weeks.size,
        )
    }
}
