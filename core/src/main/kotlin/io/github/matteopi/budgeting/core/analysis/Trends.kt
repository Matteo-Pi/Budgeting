package io.github.matteopi.budgeting.core.analysis

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import java.time.YearMonth

/** One Monday-start week's signed total per category id. */
data class WeekTotals(val weekStart: LocalDate, val totals: Map<Long, Money>)

/** A learned category's average signed weekly total in one regime. */
data class CategoryRegimeAverage(val categoryId: Long, val regime: Regime, val averageWeekly: Money, val weeksOfData: Int)

/** One calendar month's total money in vs. out. [totalIn] >= 0, [totalOut] <= 0. */
data class MonthInOut(val month: YearMonth, val totalIn: Money, val totalOut: Money)

/**
 * Historical breakdowns for the Insights screen. Excludes TRANSFER and OPENING_BALANCE rows and
 * only counts pool accounts; a RECONCILIATION row is counted under whatever category it carries
 * (always Untracked, see [io.github.matteopi.budgeting.core.reconcile.Reconcile]).
 */
object Trends {

    fun weeklyByCategory(
        accounts: List<Account>,
        categories: List<Category>,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
    ): List<WeekTotals> {
        if (to.isBefore(from)) return emptyList()
        val poolIds = poolAccountIds(accounts)
        val eligible = transactions.filter {
            it.kind != TxKind.TRANSFER && it.kind != TxKind.OPENING_BALANCE &&
                it.accountId in poolIds && it.categoryId != null &&
                !it.date.isBefore(from) && !it.date.isAfter(to)
        }
        val byWeek = eligible.groupBy { mondayOnOrBefore(it.date) }
        val weeks = mutableListOf<LocalDate>()
        var w = mondayOnOrBefore(from)
        val lastWeek = mondayOnOrBefore(to)
        while (!w.isAfter(lastWeek)) {
            weeks += w
            w = w.plusDays(7)
        }
        return weeks.map { weekStart ->
            val base = categories.associate { it.id to Money.ZERO }.toMutableMap()
            for (tx in byWeek[weekStart].orEmpty()) {
                base[tx.categoryId!!] = (base[tx.categoryId] ?: Money.ZERO) + tx.amount
            }
            WeekTotals(weekStart, base)
        }
    }

    /**
     * Average weekly total per learned category in each regime, over complete Monday-start weeks
     * fully inside the available data: from [historyStart] (or the earliest eligible transaction)
     * to the latest eligible transaction date.
     */
    fun termVsHoliday(
        accounts: List<Account>,
        categories: List<Category>,
        transactions: List<Transaction>,
        terms: List<TermPeriod>,
        historyStart: LocalDate?,
    ): List<CategoryRegimeAverage> {
        val poolIds = poolAccountIds(accounts)
        val learned = categories.filter { it.forecastMode == ForecastMode.LEARNED && !it.archived }
        if (learned.isEmpty()) return emptyList()
        val learnedIds = learned.map { it.id }.toSet()

        val eligible = transactions.filter {
            it.kind != TxKind.TRANSFER && it.kind != TxKind.OPENING_BALANCE &&
                it.accountId in poolIds && it.categoryId in learnedIds
        }
        if (eligible.isEmpty()) return emptyList()

        val dataStart = historyStart ?: eligible.minOf { it.date }
        val dataEnd = eligible.maxOf { it.date }
        if (dataEnd.isBefore(dataStart)) return emptyList()

        val weeks = mutableListOf<LocalDate>()
        var w = mondayOnOrBefore(dataStart)
        if (w.isBefore(dataStart)) w = w.plusDays(7)
        while (!w.plusDays(6).isAfter(dataEnd)) {
            weeks += w
            w = w.plusDays(7)
        }
        if (weeks.isEmpty()) return emptyList()

        val byWeek = eligible.groupBy { mondayOnOrBefore(it.date) }
        val result = mutableListOf<CategoryRegimeAverage>()
        for (category in learned) {
            for (regime in Regime.entries) {
                val weeksInRegime = weeks.filter { regimeOfWeek(it, terms) == regime }
                if (weeksInRegime.isEmpty()) continue
                var sum = 0L
                for (weekStart in weeksInRegime) {
                    for (tx in byWeek[weekStart].orEmpty()) {
                        if (tx.categoryId == category.id) sum += tx.amount.pence
                    }
                }
                result += CategoryRegimeAverage(
                    categoryId = category.id,
                    regime = regime,
                    averageWeekly = Money(sum / weeksInRegime.size),
                    weeksOfData = weeksInRegime.size,
                )
            }
        }
        return result
    }

    fun balanceHistory(
        accounts: List<Account>,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
    ): List<Pair<LocalDate, Money>> {
        if (to.isBefore(from)) return emptyList()
        val poolIds = poolAccountIds(accounts)
        val poolTx = transactions.filter { it.accountId in poolIds }.sortedBy { it.date }
        var running = 0L
        var idx = 0
        for (tx in poolTx) {
            if (tx.date.isBefore(from)) running += tx.amount.pence else break
            idx++
        }
        val result = mutableListOf<Pair<LocalDate, Money>>()
        var d = from
        while (!d.isAfter(to)) {
            while (idx < poolTx.size && !poolTx[idx].date.isAfter(d)) {
                running += poolTx[idx].amount.pence
                idx++
            }
            result += d to Money(running)
            d = d.plusDays(1)
        }
        return result
    }

    fun monthlyInOut(
        accounts: List<Account>,
        categories: List<Category>,
        transactions: List<Transaction>,
        months: Int,
        today: LocalDate,
    ): List<MonthInOut> {
        if (months <= 0) return emptyList()
        val poolIds = poolAccountIds(accounts)
        val eligible = transactions.filter {
            it.kind != TxKind.TRANSFER && it.kind != TxKind.OPENING_BALANCE && it.accountId in poolIds
        }
        val byMonth = eligible.groupBy { YearMonth.from(it.date) }
        val currentMonth = YearMonth.from(today)
        val monthsList = (0 until months).map { currentMonth.minusMonths((months - 1 - it).toLong()) }
        return monthsList.map { month ->
            val txs = byMonth[month].orEmpty()
            val totalIn = Money(txs.filter { it.amount.isPositive }.sumOf { it.amount.pence })
            val totalOut = Money(txs.filter { it.amount.isNegative }.sumOf { it.amount.pence })
            MonthInOut(month, totalIn, totalOut)
        }
    }

    private fun poolAccountIds(accounts: List<Account>): Set<Long> =
        accounts.filter { it.includeInSafeToSpend && !it.archived }.map { it.id }.toSet()

    private fun mondayOnOrBefore(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    private fun regimeOf(date: LocalDate, terms: List<TermPeriod>): Regime =
        if (terms.any { !date.isBefore(it.startDate) && !date.isAfter(it.endDate) }) Regime.TERM else Regime.HOLIDAY

    private fun regimeOfWeek(weekStart: LocalDate, terms: List<TermPeriod>): Regime {
        val termDays = (0..6).count { regimeOf(weekStart.plusDays(it.toLong()), terms) == Regime.TERM }
        return if (termDays >= 4) Regime.TERM else Regime.HOLIDAY
    }
}
