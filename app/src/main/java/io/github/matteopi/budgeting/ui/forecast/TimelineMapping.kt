package io.github.matteopi.budgeting.ui.forecast

import io.github.matteopi.budgeting.core.forecast.AccountDayForecast
import io.github.matteopi.budgeting.core.forecast.AccountForecast
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.CommittedFlow
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import java.time.LocalDate

/** One account's balance at the end of a week, for the Timeline's "Each account" view (display terms). */
data class TimelineAccountBalance(
    val accountId: Long,
    val name: String,
    val p10: Money?,
    val p50: Money,
    val p90: Money?,
    val belowFloor: Boolean,
)

/** One row of the term-aware weekly Timeline (docs/PLAN.md §6.5 "Timeline"). */
data class TimelineWeek(
    val weekStart: LocalDate,
    val regime: Regime,
    /** The term's name when [regime] is TERM and a term covers most of the week; else null. */
    val termName: String?,
    /** Committed flows dated within this week, date-sorted (only the chosen account's for a single-account view). */
    val flows: List<CommittedFlow>,
    /** Sum of expected learned spending over the days of this week (signed; negative = spending). Pool-level. */
    val expectedLearned: Money,
    /**
     * End-of-week balance, display terms: the pool's, or the chosen account's for a single-account
     * view. Null P10/P90 if Monte Carlo was skipped.
     */
    val endBalanceP10: Money?,
    val endBalanceP50: Money,
    val endBalanceP90: Money?,
    /** Money reserved in sinking funds at the end of this week. Pool-level. */
    val reserved: Money,
    /** True when this week's P10 is below the floor — highlighted in the UI. */
    val belowFloor: Boolean,
    /** For the "Each account" view: one balance per account, replacing the single balance row. Empty otherwise. */
    val accountBalances: List<TimelineAccountBalance> = emptyList(),
    /** False for a single-account view, where the pool-level "expected spending" and "reserved" rows would mislead. */
    val showPoolFigures: Boolean = true,
)

/**
 * Pure grouping of [ForecastResult.days] into Monday-start weeks, for the Timeline screen. No
 * Android/Compose imports, so it is unit-testable on the plain JVM.
 */
object TimelineMapping {
    /**
     * [view] picks what the balance rows describe: the pool ([AccountView.Total], the default), one
     * account ([AccountView.Single]) or every account ([AccountView.Each]); a view that can't be
     * drawn (no forecast for that account) falls back to the pool. Single-account balances are
     * converted with that account's own overdraft, and the floor is display £0 in every view.
     */
    fun buildWeeks(
        result: ForecastResult,
        terms: List<TermPeriod>,
        zeroPoint: ZeroPoint,
        accounts: List<Account> = emptyList(),
        view: AccountView = AccountView.Total,
    ): List<TimelineWeek> {
        // days[0] is `today` itself (no flows yet); the timeline only covers today+1 onward.
        val forecastDays = result.days.drop(1)
        if (forecastDays.isEmpty()) return emptyList()

        val floorDisplay = Balances.toDisplay(result.floor, result.poolOverdraft, zeroPoint)
        val byWeek = forecastDays.groupBy { Regimes.weekStart(it.date) }

        val single: AccountForecast? = (view as? AccountView.Single)
            ?.let { v -> result.accountForecasts.firstOrNull { it.accountId == v.accountId } }
        val singleDaysByDate: Map<LocalDate, AccountDayForecast> = single?.days?.associateBy { it.date } ?: emptyMap()
        val eachForecasts: List<AccountForecast> =
            if (view == AccountView.Each) result.accountForecasts.sortedBy { it.accountId } else emptyList()
        val namesById = accounts.associate { it.id to it.name }
        val eachDaysByAccount: Map<Long, Map<LocalDate, AccountDayForecast>> =
            eachForecasts.associate { forecast -> forecast.accountId to forecast.days.associateBy { it.date } }

        return byWeek.entries.sortedBy { it.key }.map { (weekStart, weekDays) ->
            val lastDay = weekDays.maxBy { it.date }
            val weekEnd = weekStart.plusDays(6)
            val poolFlows = result.committedFlows
                .filter { !it.date.isBefore(weekStart) && !it.date.isAfter(weekEnd) }
                .sortedBy { it.date }
            val expectedLearned = Money(weekDays.sumOf { it.expectedLearned.pence })
            val regime = Regimes.regimeOfWeek(weekStart, terms)
            val termName = if (regime == Regime.TERM) dominantTermName(weekStart, terms) else null

            val singleDay = single?.let { singleDaysByDate[lastDay.date] }
            if (single != null && singleDay != null) {
                val p10 = singleDay.p10?.let { Balances.toDisplay(it, single.overdraft, zeroPoint) }
                val p50 = Balances.toDisplay(singleDay.p50 ?: singleDay.expectedBalance, single.overdraft, zeroPoint)
                val p90 = singleDay.p90?.let { Balances.toDisplay(it, single.overdraft, zeroPoint) }
                return@map TimelineWeek(
                    weekStart = weekStart,
                    regime = regime,
                    termName = termName,
                    flows = poolFlows.filter { it.accountId == single.accountId },
                    expectedLearned = expectedLearned,
                    endBalanceP10 = p10,
                    endBalanceP50 = p50,
                    endBalanceP90 = p90,
                    reserved = lastDay.reserved,
                    belowFloor = p10 != null && p10 < Money.ZERO,
                    showPoolFigures = false,
                )
            }

            val p10 = lastDay.p10?.let { Balances.toDisplay(it, result.poolOverdraft, zeroPoint) }
            val p50 = Balances.toDisplay(lastDay.p50 ?: lastDay.expectedBalance, result.poolOverdraft, zeroPoint)
            val p90 = lastDay.p90?.let { Balances.toDisplay(it, result.poolOverdraft, zeroPoint) }

            val accountBalances = eachForecasts.mapNotNull { forecast ->
                val day = eachDaysByAccount[forecast.accountId]?.get(lastDay.date) ?: return@mapNotNull null
                val accountP10 = day.p10?.let { Balances.toDisplay(it, forecast.overdraft, zeroPoint) }
                TimelineAccountBalance(
                    accountId = forecast.accountId,
                    name = namesById[forecast.accountId] ?: "Account ${forecast.accountId}",
                    p10 = accountP10,
                    p50 = Balances.toDisplay(day.p50 ?: day.expectedBalance, forecast.overdraft, zeroPoint),
                    p90 = day.p90?.let { Balances.toDisplay(it, forecast.overdraft, zeroPoint) },
                    belowFloor = accountP10 != null && accountP10 < Money.ZERO,
                )
            }

            TimelineWeek(
                weekStart = weekStart,
                regime = regime,
                termName = termName,
                flows = poolFlows,
                expectedLearned = expectedLearned,
                endBalanceP10 = p10,
                endBalanceP50 = p50,
                endBalanceP90 = p90,
                reserved = lastDay.reserved,
                belowFloor = if (accountBalances.isNotEmpty()) accountBalances.any { it.belowFloor } else p10 != null && p10 < floorDisplay,
                accountBalances = accountBalances,
            )
        }
    }

    /** The name of whichever [TermPeriod] covers the most days of the Mon-Sun week starting [weekStart]. */
    private fun dominantTermName(weekStart: LocalDate, terms: List<TermPeriod>): String? {
        val counts = (0..6)
            .mapNotNull { Regimes.termContaining(weekStart.plusDays(it.toLong()), terms) }
            .groupingBy { it.id }
            .eachCount()
        val topTermId = counts.entries.maxByOrNull { it.value }?.key ?: return null
        return terms.firstOrNull { it.id == topTermId }?.name
    }
}
