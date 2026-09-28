package io.github.matteopi.budgeting.ui.activity

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import java.util.Locale

/** One row in the Activity list: an ordinary transaction, or a transfer's two legs shown once. */
sealed interface ActivityRow {
    val date: LocalDate

    /** Used to order rows within a day, most recent first. */
    val sortKey: Long

    data class Single(val tx: Transaction) : ActivityRow {
        override val date: LocalDate get() = tx.date
        override val sortKey: Long get() = tx.createdAtEpochMillis
    }

    /** [from] is the negative leg, [to] the positive leg. Shown once as "A -> B". */
    data class Transfer(val from: Transaction, val to: Transaction) : ActivityRow {
        override val date: LocalDate get() = from.date
        override val sortKey: Long get() = maxOf(from.createdAtEpochMillis, to.createdAtEpochMillis)
    }
}

enum class DirectionFilter { ALL, IN, OUT }

data class ActivityFilters(
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val direction: DirectionFilter = DirectionFilter.ALL,
    val search: String = "",
) {
    val isDefault: Boolean
        get() = accountId == null && categoryId == null && direction == DirectionFilter.ALL && search.isBlank()
}

data class DayGroup(val date: LocalDate, val label: String, val rows: List<ActivityRow>)

/**
 * Pure grouping, labelling and filtering logic for the Activity screen (docs/PLAN.md §6.5).
 * Kept free of Android/Compose so it can be unit-tested on the JVM.
 */
object ActivityGrouping {

    /** Pairs up transfer legs (by [Transaction.transferGroupId]) so each transfer is one row. */
    fun buildRows(transactions: List<Transaction>): List<ActivityRow> {
        val rows = mutableListOf<ActivityRow>()
        val consumedIds = mutableSetOf<Long>()
        val byGroup = transactions
            .filter { it.kind == TxKind.TRANSFER && it.transferGroupId != null }
            .groupBy { it.transferGroupId }

        for ((_, legs) in byGroup) {
            val from = legs.firstOrNull { it.amount.isNegative } ?: legs.firstOrNull()
            val to = legs.firstOrNull { it.id != from?.id }
            if (from != null && to != null) {
                rows += ActivityRow.Transfer(from = from, to = to)
                consumedIds += from.id
                consumedIds += to.id
            } else {
                // An unpaired leg (shouldn't normally happen): show it on its own rather than drop it.
                legs.forEach { leg ->
                    rows += ActivityRow.Single(leg)
                    consumedIds += leg.id
                }
            }
        }

        transactions.filter { it.id !in consumedIds }.forEach { rows += ActivityRow.Single(it) }

        return rows.sortedWith(compareByDescending<ActivityRow> { it.date }.thenByDescending { it.sortKey })
    }

    /** Groups (already date-sorted) [rows] into day buckets, most recent day first. */
    fun groupByDay(rows: List<ActivityRow>, today: LocalDate): List<DayGroup> {
        val grouped = LinkedHashMap<LocalDate, MutableList<ActivityRow>>()
        for (row in rows) {
            grouped.getOrPut(row.date) { mutableListOf() } += row
        }
        return grouped.map { (date, rowsForDay) -> DayGroup(date, dayLabel(date, today), rowsForDay) }
    }

    /** "Today", "Yesterday", or e.g. "Mon 28 Sep". */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.toWeekdayShortLabel()
    }

    fun matches(row: ActivityRow, filters: ActivityFilters, categoriesById: Map<Long, Category>): Boolean {
        if (filters.search.isNotBlank()) {
            val query = filters.search.trim().lowercase(Locale.UK)
            val text = when (row) {
                is ActivityRow.Single -> row.tx.label.ifBlank { categoriesById[row.tx.categoryId]?.name.orEmpty() }
                is ActivityRow.Transfer -> row.from.label
            }.lowercase(Locale.UK)
            if (!text.contains(query)) return false
        }
        if (filters.accountId != null) {
            val match = when (row) {
                is ActivityRow.Single -> row.tx.accountId == filters.accountId
                is ActivityRow.Transfer -> row.from.accountId == filters.accountId || row.to.accountId == filters.accountId
            }
            if (!match) return false
        }
        if (filters.categoryId != null) {
            val match = row is ActivityRow.Single && row.tx.categoryId == filters.categoryId
            if (!match) return false
        }
        if (filters.direction != DirectionFilter.ALL) {
            when (row) {
                is ActivityRow.Single -> {
                    val isIn = row.tx.amount.isPositive
                    if (filters.direction == DirectionFilter.IN && !isIn) return false
                    if (filters.direction == DirectionFilter.OUT && isIn) return false
                }
                is ActivityRow.Transfer -> return false
            }
        }
        return true
    }
}
