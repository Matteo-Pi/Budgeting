package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One complete Mon–Sun week inside the history window, tagged with its majority regime. */
data class HistoryWeek(val weekStart: LocalDate, val regime: Regime)

/**
 * The learned-spending history window: complete Mon–Sun weeks, per learned category, with
 * reconciliation gaps already spread across their period. See `docs/PLAN.md` §5.6.
 */
data class History(
    /** `settings.historyStartDate`, or the earliest learned-transaction date, or null (no history). */
    val historyStart: LocalDate?,
    /** Complete weeks inside the window, ascending, capped to the most recent
     * [ForecastConstants.MAX_HISTORY_WEEKS]. */
    val weeks: List<HistoryWeek>,
    /** categoryId -> weekly signed totals, aligned index-for-index with [weeks]. Every LEARNED
     * category has an entry (all-zero when it has no history). */
    val weeklyByCategory: Map<Long, List<Money>>,
    /** categoryId -> date -> signed amount that day, for days inside the [weeks] window only. */
    val dailyByCategory: Map<Long, Map<LocalDate, Money>>,
    /**
     * categoryId -> accountId -> sum of |amount| (pence) of the very transactions behind
     * [dailyByCategory] (window only, reconciliation spreading included): the weights of the
     * per-account split of learned flows (`docs/PLAN.md` §5.11a). Categories without any
     * amount in the window have no entry.
     */
    val absByCategoryAccount: Map<Long, Map<Long, Long>> = emptyMap(),
) {
    fun dayAmount(categoryId: Long, date: LocalDate): Money =
        dailyByCategory[categoryId]?.get(date) ?: Money.ZERO
}

object HistoryBuilder {
    fun build(input: ForecastInput): History {
        val poolIds = Balances.pool(input.accounts).map { it.id }.toSet()
        val learnedCategories = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }
        val learnedCategoryIds = learnedCategories.map { it.id }.toSet()

        val learnedTransactions = input.transactions.filter {
            (it.kind == TxKind.NORMAL || it.kind == TxKind.RECONCILIATION) &&
                it.accountId in poolIds &&
                it.categoryId != null && it.categoryId in learnedCategoryIds &&
                it.recurringRuleId == null && it.scheduledItemId == null
        }

        val historyStart = input.settings.historyStartDate ?: learnedTransactions.minOfOrNull { it.date }
        if (historyStart == null) return empty(learnedCategoryIds, null)

        val startMonday = Regimes.weekStart(historyStart)
        val firstMonday = if (startMonday == historyStart) startMonday else startMonday.plusWeeks(1)
        // The current (in-progress) week's Sunday is never before today, so the most recent
        // complete week is always the one before it.
        val lastMonday = Regimes.weekStart(input.today).minusWeeks(1)

        val allMondays = mutableListOf<LocalDate>()
        var m = firstMonday
        while (!m.isAfter(lastMonday)) {
            allMondays.add(m)
            m = m.plusWeeks(1)
        }
        val mondays = allMondays.takeLast(ForecastConstants.MAX_HISTORY_WEEKS)
        if (mondays.isEmpty()) return empty(learnedCategoryIds, historyStart)

        val weeks = mondays.map { HistoryWeek(it, Regimes.regimeOfWeek(it, input.termPeriods)) }
        val windowStart = weeks.first().weekStart
        val windowEndExclusive = weeks.last().weekStart.plusDays(7)

        val daily = HashMap<Long, HashMap<LocalDate, Long>>()
        val absByAccount = HashMap<Long, HashMap<Long, Long>>()
        fun add(categoryId: Long, accountId: Long, date: LocalDate, pence: Long) {
            if (date.isBefore(windowStart) || !date.isBefore(windowEndExclusive)) return
            val byDate = daily.getOrPut(categoryId) { HashMap() }
            byDate[date] = (byDate[date] ?: 0L) + pence
            val byAccount = absByAccount.getOrPut(categoryId) { HashMap() }
            byAccount[accountId] = (byAccount[accountId] ?: 0L) + Math.abs(pence)
        }

        val reconciliationByTxId = input.reconciliations
            .filter { it.transactionId != null }
            .associateBy { it.transactionId }

        for (tx in learnedTransactions) {
            val categoryId = tx.categoryId ?: continue
            if (tx.kind == TxKind.NORMAL) {
                add(categoryId, tx.accountId, tx.date, tx.amount.pence)
                continue
            }
            // RECONCILIATION: statistics only, spread evenly (whole pence, remainder on the
            // last day) across the reconciliation's period -- never the ledger itself.
            val reconciliation = reconciliationByTxId[tx.id]
            val periodStart = reconciliation?.periodStart ?: tx.date.minusDays(6)
            val periodEnd = tx.date
            val totalDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1
            if (totalDays <= 0) {
                add(categoryId, tx.accountId, tx.date, tx.amount.pence)
                continue
            }
            val base = tx.amount.pence / totalDays
            val remainder = tx.amount.pence - base * totalDays
            var d = periodStart
            while (!d.isAfter(periodEnd)) {
                val pence = if (d == periodEnd) base + remainder else base
                add(categoryId, tx.accountId, d, pence)
                d = d.plusDays(1)
            }
        }

        val weeklyByCategory = HashMap<Long, List<Money>>()
        val dailyByCategoryMoney = HashMap<Long, Map<LocalDate, Money>>()
        for (category in learnedCategories) {
            val byDate = daily[category.id].orEmpty()
            dailyByCategoryMoney[category.id] = byDate.mapValues { Money(it.value) }
            weeklyByCategory[category.id] = weeks.map { week ->
                var sum = 0L
                for (i in 0..6) sum += byDate[week.weekStart.plusDays(i.toLong())] ?: 0L
                Money(sum)
            }
        }

        return History(historyStart, weeks, weeklyByCategory, dailyByCategoryMoney, absByAccount)
    }

    private fun empty(learnedCategoryIds: Set<Long>, historyStart: LocalDate?): History =
        History(
            historyStart = historyStart,
            weeks = emptyList(),
            weeklyByCategory = learnedCategoryIds.associateWith { emptyList<Money>() },
            dailyByCategory = emptyMap(),
        )
}
