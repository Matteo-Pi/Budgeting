package io.github.matteopi.budgeting.ui.insights

import io.github.matteopi.budgeting.core.analysis.Trends
import io.github.matteopi.budgeting.core.analysis.WeekTotals
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AccountView
import java.time.LocalDate
import java.time.YearMonth

/** This calendar month's total for a category vs. the average of the other months in the period. */
data class CategoryMonthComparison(val categoryId: Long, val thisMonth: Money, val average: Money)

/** One balance-history line in display terms; [accountId] is null for the pool total. */
data class BalanceHistoryLine(val accountId: Long?, val label: String, val points: List<Pair<LocalDate, Money>>)

/**
 * Pure helpers for the Insights screen, built only on [io.github.matteopi.budgeting.core.analysis.Trends]'
 * public output. No Android/Compose imports, so it is unit-testable on the plain JVM.
 */
object InsightsMapping {

    /** For each [categoryIds], this calendar month's total vs. the average of the period's other months. */
    fun thisMonthVsAverage(weeklyByCategory: List<WeekTotals>, categoryIds: List<Long>, today: LocalDate): List<CategoryMonthComparison> {
        val currentMonth = YearMonth.from(today)
        val byMonth = weeklyByCategory.groupBy { YearMonth.from(it.weekStart) }
        val otherMonths = byMonth.keys.filter { it != currentMonth }

        return categoryIds.map { categoryId ->
            val thisMonthTotal = Money(byMonth[currentMonth].orEmpty().sumOf { it.totals[categoryId]?.pence ?: 0L })
            val average = if (otherMonths.isEmpty()) {
                Money.ZERO
            } else {
                val totalOverOtherMonths = otherMonths.sumOf { month ->
                    byMonth[month].orEmpty().sumOf { it.totals[categoryId]?.pence ?: 0L }
                }
                Money(totalOverOtherMonths / otherMonths.size)
            }
            CategoryMonthComparison(categoryId, thisMonthTotal, average)
        }
    }

    /**
     * The balance-history line(s) for [view], from [from] to [to], converted to display terms:
     * every non-archived account summed ([AccountView.All]), the safe-to-spend accounts summed
     * ([AccountView.Pool]), one account ([AccountView.Single], converted with its own overdraft) or
     * one line per non-archived account in creation order ([AccountView.Each]). A single account
     * that is missing or archived falls back to the all-accounts line.
     *
     * A single account's history reuses [Trends.balanceHistory] with a one-account list (the
     * account is treated as a pool member for that call), so it counts every transaction on it,
     * transfers and opening balance included, like the pool line does.
     */
    fun balanceHistory(
        view: AccountView,
        accounts: List<Account>,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        zeroPoint: ZeroPoint,
    ): List<BalanceHistoryLine> {
        val live = accounts.filter { !it.archived }.sortedBy { it.id }
        return when (view) {
            AccountView.All -> listOf(allAccountsLine(live, transactions, from, to, zeroPoint))
            AccountView.Pool -> listOf(poolLine(accounts, transactions, from, to, zeroPoint))
            is AccountView.Single -> {
                val account = live.firstOrNull { it.id == view.accountId }
                if (account == null) {
                    listOf(allAccountsLine(live, transactions, from, to, zeroPoint))
                } else {
                    listOf(accountLine(account, transactions, from, to, zeroPoint))
                }
            }
            AccountView.Each ->
                if (live.isEmpty()) {
                    listOf(allAccountsLine(live, transactions, from, to, zeroPoint))
                } else {
                    live.map { accountLine(it, transactions, from, to, zeroPoint) }
                }
        }
    }

    /** The accounts included in safe-to-spend, summed: the classic total line. */
    private fun poolLine(
        accounts: List<Account>,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        zeroPoint: ZeroPoint,
    ): BalanceHistoryLine {
        val overdraft = Balances.poolOverdraft(accounts)
        val points = Trends.balanceHistory(accounts, transactions, from, to)
            .map { (date, raw) -> date to Balances.toDisplay(raw, overdraft, zeroPoint) }
        return BalanceHistoryLine(accountId = null, label = "Safe-to-spend accounts", points = points)
    }

    /** Every non-archived account in [live], summed and converted with their combined overdraft. */
    private fun allAccountsLine(
        live: List<Account>,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        zeroPoint: ZeroPoint,
    ): BalanceHistoryLine {
        // Trends.balanceHistory sums the safe-to-spend accounts it is given, so treat each live one as such.
        val everyAccount = live.map { it.copy(includeInSafeToSpend = true, archived = false) }
        val overdraft = Balances.poolOverdraft(everyAccount)
        val points = Trends.balanceHistory(everyAccount, transactions, from, to)
            .map { (date, raw) -> date to Balances.toDisplay(raw, overdraft, zeroPoint) }
        return BalanceHistoryLine(accountId = null, label = "All accounts", points = points)
    }

    private fun accountLine(
        account: Account,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        zeroPoint: ZeroPoint,
    ): BalanceHistoryLine {
        val oneAccount = listOf(account.copy(includeInSafeToSpend = true, archived = false))
        val points = Trends.balanceHistory(oneAccount, transactions.filter { it.accountId == account.id }, from, to)
            .map { (date, raw) -> date to Balances.toDisplay(raw, account.overdraftLimit, zeroPoint) }
        return BalanceHistoryLine(accountId = account.id, label = account.name, points = points)
    }

    /** The one weekly total closest to the middle of a sorted-by-value list — a simple, robust median. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
