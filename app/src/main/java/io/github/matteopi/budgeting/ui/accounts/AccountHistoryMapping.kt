package io.github.matteopi.budgeting.ui.accounts

import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.ui.activity.ActivityGrouping
import io.github.matteopi.budgeting.ui.activity.ActivityRow
import io.github.matteopi.budgeting.ui.activity.DirectionFilter
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** The chart/summary ranges on the history screen. */
enum class HistoryRange(val label: String) {
    MONTH_1("1M"),
    MONTHS_3("3M"),
    MONTHS_6("6M"),
    YEAR_1("1Y"),
    ALL("All"),
    ;

    /** The first day of the range ending [today]; [earliest] (the account's first transaction date) is where [ALL] starts. */
    fun startDate(today: LocalDate, earliest: LocalDate?): LocalDate = when (this) {
        MONTH_1 -> today.minusMonths(1)
        MONTHS_3 -> today.minusMonths(3)
        MONTHS_6 -> today.minusMonths(6)
        YEAR_1 -> today.minusYears(1)
        ALL -> earliest?.takeIf { !it.isAfter(today) } ?: today
    }
}

/** What hides rows on the history screen. Filters never change running balances or month totals. */
data class HistoryFilters(
    /** Matches the label, the category name or the note, ignoring case. */
    val search: String = "",
    val categoryId: Long? = null,
    val direction: DirectionFilter = DirectionFilter.ALL,
) {
    val isDefault: Boolean get() = search.isBlank() && categoryId == null && direction == DirectionFilter.ALL
}

/**
 * One transaction on the history list. [balanceAfter] is the account's balance after it, in display terms (the
 * same zero point as the account page); [badges] say what kind of entry it is; [editRow] is what the edit sheet
 * opens (both legs of a transfer when the other leg exists).
 */
data class HistoryRow(
    val tx: Transaction,
    val title: String,
    val subtitle: String?,
    val balanceAfter: Money,
    val badges: List<String>,
    val editRow: ActivityRow,
) {
    /** "Balance £59.83". */
    val balanceText: String get() = "Balance ${balanceAfter.format()}"
}

/** The visible rows of one day, newest first. [label] is Activity's day header ("Today", "Yesterday", "Mon 28 Sep"). */
data class HistoryDay(val date: LocalDate, val label: String, val rows: List<HistoryRow>)

/**
 * One calendar month. The totals cover every transaction of the month, whatever the filters hide; opening
 * balances are not money moving, so they count in neither [moneyIn] nor [moneyOut]. [endBalance] is the balance
 * after the month's last transaction (display terms). [days] holds the visible rows only, newest day first.
 */
data class HistoryMonth(
    val month: YearMonth,
    val moneyIn: Money,
    val moneyOut: Money,
    val endBalance: Money,
    val days: List<HistoryDay>,
) {
    /** "October 2026". */
    val label: String get() = AccountHistoryMapping.monthLabel(month)

    /** "October 2026 · in £500.00 · out £485.21 · ends at £14.79". */
    val headerText: String
        get() = "$label · in ${moneyIn.format()} · out ${moneyOut.format()} · ends at ${endBalance.format()}"
}

/** The list for the screen: months newest first, and how many of the account's transactions are shown. */
data class AccountHistory(val months: List<HistoryMonth>, val totalRows: Int, val shownRows: Int)

/**
 * The summary for the chosen range, from [from] to [to] inclusive, in display terms. An opening balance dated inside
 * the range is part of the starting position, so [startBalance] + [moneyIn] - [moneyOut] = [endBalance] always.
 */
data class HistorySummary(
    val from: LocalDate,
    val to: LocalDate,
    val startBalance: Money,
    val endBalance: Money,
    val moneyIn: Money,
    val moneyOut: Money,
)

/**
 * The pure part of an account's history screen (docs/PLAN.md §6.5): running balances, month and day grouping,
 * month totals, filters and the range summary. No Android or Compose, so it is tested on the plain JVM.
 *
 * **Order and running balance.** All of the account's transactions, of every kind, are put in date order, with
 * same-day rows ordered by `createdAtEpochMillis` and then id; a row's balance is the sum of everything up to
 * and including it, converted to display terms with [Balances.toDisplay] (the account's own overdraft limit
 * under the "overdraft limit used up" zero point).
 *
 * **Filters hide rows but change nothing else.** Running balances and month totals are always worked out from
 * all of the account's rows (a balance is a fact about the account, not about a search); only the rows listed
 * shrink, and a month with no visible rows is left out.
 */
object AccountHistoryMapping {
    private val ORDER = compareBy<Transaction>({ it.date }, { it.createdAtEpochMillis }, { it.id })

    private val MONTH_NAMES = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    const val NOT_IN_APP_BADGE = "Transfer · other account not in the app"

    /** "October 2026". */
    fun monthLabel(month: YearMonth): String = "${MONTH_NAMES[month.monthValue - 1]} ${month.year}"

    /** The date of the account's first transaction, or null when it has none. */
    fun earliestDate(accountId: Long, transactions: List<Transaction>): LocalDate? =
        transactions.filter { it.accountId == accountId }.minOfOrNull { it.date }

    /** The account's transactions in running-balance order: date, then creation time, then id. */
    fun ordered(accountId: Long, transactions: List<Transaction>): List<Transaction> =
        transactions.filter { it.accountId == accountId }.sortedWith(ORDER)

    /**
     * The history list for [account]. [transactions] are every account's (the other leg of a transfer is looked up in
     * them); [accounts] and [categories] give names; [today] decides the day headers ("Today", "Yesterday", ...).
     */
    fun build(
        account: Account,
        transactions: List<Transaction>,
        accounts: List<Account>,
        categories: List<Category>,
        zeroPoint: ZeroPoint,
        filters: HistoryFilters,
        today: LocalDate,
    ): AccountHistory {
        val accountNames = accounts.associate { it.id to it.name }
        val categoriesById = categories.associateBy { it.id }
        val legsByGroup = transactions
            .filter { it.kind == TxKind.TRANSFER && it.transferGroupId != null }
            .groupBy { it.transferGroupId }

        var raw = 0L
        val rows = ordered(account.id, transactions).map { tx ->
            raw += tx.amount.pence
            val partner = tx.transferGroupId
                ?.takeIf { tx.kind == TxKind.TRANSFER }
                ?.let { group -> legsByGroup[group]?.firstOrNull { it.id != tx.id } }
            toRow(
                tx = tx,
                balanceAfter = Balances.toDisplay(Money(raw), account.overdraftLimit, zeroPoint),
                partner = partner,
                accountNames = accountNames,
                categoriesById = categoriesById,
            )
        }

        val months = rows.groupBy { YearMonth.from(it.tx.date) }.toSortedMap().map { (month, monthRows) ->
            val counted = monthRows.filter { it.tx.kind != TxKind.OPENING_BALANCE }
            val visible = monthRows.filter { matches(it, filters, categoriesById) }
            HistoryMonth(
                month = month,
                moneyIn = Money(counted.filter { it.tx.amount.isPositive }.sumOf { it.tx.amount.pence }),
                moneyOut = Money(counted.filter { it.tx.amount.isNegative }.sumOf { -it.tx.amount.pence }),
                endBalance = monthRows.last().balanceAfter,
                days = visible.asReversed().groupBy { it.tx.date }.map { (date, dayRows) ->
                    HistoryDay(date = date, label = ActivityGrouping.dayLabel(date, today), rows = dayRows)
                },
            )
        }.filter { it.days.isNotEmpty() }.asReversed()

        return AccountHistory(
            months = months,
            totalRows = rows.size,
            shownRows = months.sumOf { month -> month.days.sumOf { it.rows.size } },
        )
    }

    /** The summary of [account]'s money from [from] to [to] (see [HistorySummary]). */
    fun summary(
        account: Account,
        transactions: List<Transaction>,
        zeroPoint: ZeroPoint,
        from: LocalDate,
        to: LocalDate,
    ): HistorySummary {
        val own = transactions.filter { it.accountId == account.id }
        val before = own.filter { it.date.isBefore(from) }.sumOf { it.amount.pence }
        val inRange = own.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val opening = inRange.filter { it.kind == TxKind.OPENING_BALANCE }.sumOf { it.amount.pence }
        val moving = inRange.filter { it.kind != TxKind.OPENING_BALANCE }
        val upToEnd = own.filter { !it.date.isAfter(to) }.sumOf { it.amount.pence }
        return HistorySummary(
            from = from,
            to = to,
            startBalance = Balances.toDisplay(Money(before + opening), account.overdraftLimit, zeroPoint),
            endBalance = Balances.toDisplay(Money(upToEnd), account.overdraftLimit, zeroPoint),
            moneyIn = Money(moving.filter { it.amount.isPositive }.sumOf { it.amount.pence }),
            moneyOut = Money(moving.filter { it.amount.isNegative }.sumOf { -it.amount.pence }),
        )
    }

    /** True if [row] passes [filters] (search over label, category name and note; category; direction). */
    fun matches(row: HistoryRow, filters: HistoryFilters, categoriesById: Map<Long, Category>): Boolean {
        val query = filters.search.trim().lowercase(Locale.UK)
        if (query.isNotEmpty()) {
            val categoryName = row.tx.categoryId?.let { categoriesById[it]?.name }.orEmpty()
            val haystack = listOf(row.tx.label, categoryName, row.tx.note).joinToString("\n").lowercase(Locale.UK)
            if (!haystack.contains(query)) return false
        }
        if (filters.categoryId != null && row.tx.categoryId != filters.categoryId) return false
        when (filters.direction) {
            DirectionFilter.ALL -> Unit
            DirectionFilter.IN -> if (!row.tx.amount.isPositive) return false
            DirectionFilter.OUT -> if (!row.tx.amount.isNegative) return false
        }
        return true
    }

    private fun toRow(
        tx: Transaction,
        balanceAfter: Money,
        partner: Transaction?,
        accountNames: Map<Long, String>,
        categoriesById: Map<Long, Category>,
    ): HistoryRow {
        val category = tx.categoryId?.let { categoriesById[it] }
        val title = when (tx.kind) {
            TxKind.TRANSFER -> tx.label.ifBlank { "Transfer" }
            TxKind.OPENING_BALANCE -> tx.label.ifBlank { "Opening balance" }
            TxKind.RECONCILIATION -> tx.label.ifBlank { "Balance check" }
            TxKind.NORMAL -> tx.label.ifBlank { category?.name ?: "Untitled" }
        }
        val badges = buildList {
            if (tx.kind != TxKind.OPENING_BALANCE && (tx.importBatchId != null || tx.importKey != null)) add("Imported")
            if (tx.receiptId != null) add("Receipt")
            if (tx.kind == TxKind.TRANSFER) {
                if (partner == null) {
                    add(NOT_IN_APP_BADGE)
                } else {
                    val other = accountNames[partner.accountId] ?: "another account"
                    add(if (tx.amount.isNegative) "Transfer to $other" else "Transfer from $other")
                }
            }
            if (tx.kind == TxKind.OPENING_BALANCE) add("Opening balance")
            if (tx.kind == TxKind.RECONCILIATION) add("Balance check")
        }
        val editRow = if (partner != null) {
            ActivityGrouping.buildRows(listOf(tx, partner)).first()
        } else {
            ActivityRow.Single(tx)
        }
        return HistoryRow(
            tx = tx,
            title = title,
            subtitle = if (tx.kind == TxKind.NORMAL || tx.kind == TxKind.RECONCILIATION) category?.name else null,
            balanceAfter = balanceAfter,
            badges = badges,
            editRow = editRow,
        )
    }
}
