package io.github.matteopi.budgeting.ui.home

import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.core.model.sum
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.collapsePool
import io.github.matteopi.budgeting.data.settings.resolveForBalance

/** The "Your money" number and the sparkline under it, for the chosen [view], in display terms. */
data class HomeBalance(
    /** The view actually shown: the requested one, or [AccountView.All] if that account is gone. */
    val view: AccountView,
    val display: Money,
    /** Where "£0" sits on the sparkline. */
    val floorDisplay: Money,
    /** One expected balance per week for the next weeks (empty until there is a forecast). */
    val sparkline: List<Money>,
)

/** The two lines under the safe-to-spend figure. */
data class HeroDetail(
    /** "£10 a day · Rent on 1 Oct": the per-day figure and, when the engine names one, what limits it. */
    val perDayLine: String,
    /** "12 days to Student loan", or null when no major income is coming. */
    val nextIncomeLine: String?,
)

/**
 * Pure mapping for the Home screen: everything is worked out from one [ForecastInput] and the
 * [ForecastResult] computed from that same input, never from two separate subscriptions.
 */
object HomeMapping {
    private const val SPARKLINE_WEEKS = 8

    /**
     * The balance for [requestedView]: every non-archived account ([AccountView.All]), the
     * safe-to-spend accounts ([AccountView.Pool]) or one account converted with its own overdraft.
     * A view that points at a missing or archived account (or [AccountView.Each], which a single
     * number can't show) becomes [AccountView.All], and so does [AccountView.Pool] when no account
     * is left out of safe-to-spend (they are identical then). Balances are read from the ledger in
     * [input], so they always agree with the account rows drawn from the same input.
     */
    fun balance(requestedView: AccountView, input: ForecastInput, result: ForecastResult?): HomeBalance {
        val zeroPoint = input.settings.zeroPoint
        val view = requestedView.resolveForBalance(input.accounts).collapsePool(input.accounts)
        val raw = Balances.balancesByAccount(input.transactions, input.today)
        val single = (view as? AccountView.Single)?.let { v -> input.accounts.firstOrNull { it.id == v.accountId } }

        if (single != null) {
            val overdraft = single.overdraftLimit
            val accountDays = result?.accountForecasts?.firstOrNull { it.accountId == single.id }?.days
            return HomeBalance(
                view = view,
                display = Balances.toDisplay(raw[single.id] ?: Money.ZERO, overdraft, zeroPoint),
                floorDisplay = Balances.toDisplay(floorRaw(overdraft, zeroPoint), overdraft, zeroPoint),
                sparkline = weeklySamples(accountDays?.map { it.expectedBalance }.orEmpty())
                    .map { Balances.toDisplay(it, overdraft, zeroPoint) },
            )
        }

        if (view == AccountView.All) return allAccountsBalance(input, result, raw)

        val poolOverdraft = Balances.poolOverdraft(input.accounts)
        val poolRaw = Balances.poolBalance(input.accounts, input.transactions, input.today)
        return HomeBalance(
            view = AccountView.Pool,
            display = Balances.toDisplay(poolRaw, poolOverdraft, zeroPoint),
            floorDisplay = Balances.toDisplay(Balances.floor(input.accounts, zeroPoint), poolOverdraft, zeroPoint),
            sparkline = weeklySamples(result?.days?.map { it.expectedBalance }.orEmpty())
                .map { Balances.toDisplay(it, poolOverdraft, zeroPoint) },
        )
    }

    /**
     * The sum of every non-archived account. The sparkline starts from the pool's expected balance
     * and adds the accounts outside the pool, which have no learned flows (only committed ones), so
     * their expected balance is exactly their balance on each day.
     */
    private fun allAccountsBalance(
        input: ForecastInput,
        result: ForecastResult?,
        raw: Map<Long, Money>,
    ): HomeBalance {
        val zeroPoint = input.settings.zeroPoint
        val live = input.accounts.filter { !it.archived }
        val overdraft = live.map { it.overdraftLimit }.sum()
        val allRaw = live.map { raw[it.id] ?: Money.ZERO }.sum()
        val outsidePool = result?.accountForecasts.orEmpty().filter { !it.inPool }
        val dailyAll = result?.days.orEmpty().mapIndexed { index, day ->
            day.expectedBalance + outsidePool.map { it.days.getOrNull(index)?.expectedBalance ?: Money.ZERO }.sum()
        }
        return HomeBalance(
            view = AccountView.All,
            display = Balances.toDisplay(allRaw, overdraft, zeroPoint),
            floorDisplay = Balances.toDisplay(floorRaw(overdraft, zeroPoint), overdraft, zeroPoint),
            sparkline = weeklySamples(dailyAll).map { Balances.toDisplay(it, overdraft, zeroPoint) },
        )
    }

    /** The raw floor for one account: £0, or minus its own overdraft when £0 means "overdraft used up". */
    private fun floorRaw(overdraft: Money, zeroPoint: ZeroPoint): Money = when (zeroPoint) {
        ZeroPoint.ACCOUNT_BALANCE -> Money.ZERO
        ZeroPoint.OVERDRAFT_LIMIT -> -overdraft
    }

    /** The balance at the end of each of the first weeks: days 7, 14, … of a series that starts today. */
    private fun weeklySamples(dailyFromToday: List<Money>): List<Money> =
        dailyFromToday.filterIndexed { index, _ -> (index + 1) % 7 == 0 }.take(SPARKLINE_WEEKS)

    /** The hero's supporting lines: what limits the figure, and how long until the next money in. */
    fun heroDetail(safeToSpend: SafeToSpend): HeroDetail {
        val perDay = "${safeToSpend.perDay.format(showPence = false)} a day"
        val reason = safeToSpend.bindingReason?.takeIf { it.isNotBlank() }
        val days = safeToSpend.daysUntilNextMajorIncome
        val incomeLabel = safeToSpend.nextMajorIncome?.label?.takeIf { it.isNotBlank() } ?: "next money in"
        return HeroDetail(
            perDayLine = if (reason != null) "$perDay · $reason" else perDay,
            nextIncomeLine = days?.let { "$it ${if (it == 1) "day" else "days"} to $incomeLabel" },
        )
    }
}
