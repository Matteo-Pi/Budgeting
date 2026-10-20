package io.github.matteopi.budgeting.ui.accounts

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.ui.activity.ActivityRow
import io.github.matteopi.budgeting.ui.activity.DirectionFilter
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountHistoryMappingTest {

    private val today = LocalDate.of(2026, 10, 16) // a Friday

    private val current = Account(id = 1, name = "Lloyds current", type = AccountType.CURRENT, overdraftLimit = Money.pounds(200))
    private val savings = Account(id = 2, name = "Savings", type = AccountType.SAVINGS)
    private val accounts = listOf(current, savings)

    private val groceries = Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
    private val wages = Category(id = 2, name = "Wages", flow = Flow.IN, forecastMode = ForecastMode.LEARNED)
    private val untracked = Category(id = 3, name = "Untracked", flow = Flow.BOTH, forecastMode = ForecastMode.LEARNED)
    private val categories = listOf(groceries, wages, untracked)

    private var nextId = 1L

    private fun tx(
        pence: Long,
        date: LocalDate,
        accountId: Long = 1,
        categoryId: Long? = null,
        label: String = "",
        note: String = "",
        kind: TxKind = TxKind.NORMAL,
        group: String? = null,
        createdAt: Long = 0,
        id: Long = nextId++,
        importKey: String? = null,
        importBatchId: Long? = null,
        receiptId: Long? = null,
    ) = Transaction(
        id = id,
        accountId = accountId,
        amount = Money(pence),
        date = date,
        createdAtEpochMillis = createdAt,
        categoryId = categoryId,
        label = label,
        note = note,
        kind = kind,
        transferGroupId = group,
        importKey = importKey,
        importBatchId = importBatchId,
        receiptId = receiptId,
    )

    private fun history(
        transactions: List<Transaction>,
        filters: HistoryFilters = HistoryFilters(),
        zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
        account: Account = current,
    ) = AccountHistoryMapping.build(account, transactions, accounts, categories, zeroPoint, filters, today)

    private fun AccountHistory.rows(): List<HistoryRow> = months.flatMap { month -> month.days.flatMap { it.rows } }

    private fun d(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)

    // ---- the October example -----------------------------------------------------------------------

    /** Opening £0 on 30 Sep, then £500 in and £485.21 out in October: ends at £14.79 (like the Santander statement). */
    private val october = listOf(
        tx(0, d(9, 30), kind = TxKind.OPENING_BALANCE, label = "Opening balance"),
        tx(50_000, d(10, 2), categoryId = 2, label = "Cafe shifts"),
        tx(-20_000, d(10, 5), categoryId = 1, label = "Tesco"),
        tx(-28_521, d(10, 9), label = "Rent"),
    )

    @Test
    fun `a month header reads in, out and ends at`() {
        val months = history(october).months

        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.month })
        val oct = months.first()
        assertEquals("October 2026", oct.label)
        assertEquals(Money(50_000), oct.moneyIn)
        assertEquals(Money(48_521), oct.moneyOut)
        assertEquals(Money(1_479), oct.endBalance)
        assertEquals("October 2026 · in £500.00 · out £485.21 · ends at £14.79", oct.headerText)
    }

    @Test
    fun `an opening balance is not money in or out`() {
        val sep = history(october).months.last()
        assertEquals("September 2026 · in £0.00 · out £0.00 · ends at £0.00", sep.headerText)

        val big = history(listOf(tx(486_215, d(9, 30), kind = TxKind.OPENING_BALANCE, label = "Opening balance"))).months.single()
        assertEquals(Money.ZERO, big.moneyIn)
        assertEquals(Money.ZERO, big.moneyOut)
        assertEquals(Money(486_215), big.endBalance)
    }

    @Test
    fun `every month has a name`() {
        val names = (1..12).map { AccountHistoryMapping.monthLabel(YearMonth.of(2026, it)) }
        assertEquals(
            listOf(
                "January 2026", "February 2026", "March 2026", "April 2026", "May 2026", "June 2026",
                "July 2026", "August 2026", "September 2026", "October 2026", "November 2026", "December 2026",
            ),
            names,
        )
    }

    // ---- running balance ----------------------------------------------------------------------------

    @Test
    fun `balances run in date order whatever order the transactions come in`() {
        val shuffled = october.reversed()
        val rows = history(shuffled).rows() // newest first
        assertEquals(listOf("Rent", "Tesco", "Cafe shifts", "Opening balance"), rows.map { it.title })
        assertEquals(listOf(1_479L, 30_000L, 50_000L, 0L), rows.map { it.balanceAfter.pence })
    }

    @Test
    fun `same-day rows are ordered by creation time, then id`() {
        val a = tx(-1_000, d(10, 6), label = "A", createdAt = 300, id = 30)
        val b = tx(-2_000, d(10, 6), label = "B", createdAt = 100, id = 31)
        val c = tx(-4_000, d(10, 6), label = "C", createdAt = 300, id = 29)
        val opening = tx(100_000, d(10, 1), kind = TxKind.OPENING_BALANCE, createdAt = 1, id = 1)

        val oldestFirst = history(listOf(a, b, c, opening)).rows().asReversed()

        // B (created first), then C (same creation time as A but the lower id), then A.
        assertEquals(listOf("", "B", "C", "A"), oldestFirst.map { it.tx.label })
        assertEquals(listOf(100_000L, 98_000L, 94_000L, 93_000L), oldestFirst.map { it.balanceAfter.pence })
    }

    @Test
    fun `every kind of transaction counts in the running balance`() {
        val rows = history(
            listOf(
                tx(10_000, d(10, 1), kind = TxKind.OPENING_BALANCE, label = "Opening balance"),
                tx(-500, d(10, 2), categoryId = 1, label = "Coffee"),
                tx(-2_000, d(10, 3), accountId = 1, kind = TxKind.TRANSFER, group = "out", label = "To savings"),
                tx(2_000, d(10, 3), accountId = 2, kind = TxKind.TRANSFER, group = "out", label = "To savings"),
                tx(300, d(10, 4), accountId = 1, kind = TxKind.TRANSFER, group = "in", label = "From savings"),
                tx(-300, d(10, 4), accountId = 2, kind = TxKind.TRANSFER, group = "in", label = "From savings"),
                tx(-7_930, d(10, 5), categoryId = 3, kind = TxKind.RECONCILIATION, label = "Balance update"),
            ),
        ).rows().asReversed()

        assertEquals(
            listOf("Opening balance", "Coffee", "To savings", "From savings", "Balance update"),
            rows.map { it.title },
        )
        assertEquals(listOf(10_000L, 9_500L, 7_500L, 7_800L, -130L), rows.map { it.balanceAfter.pence })
    }

    @Test
    fun `the other accounts' rows never enter this account's balance`() {
        val rows = history(october + tx(-99_999, d(10, 3), accountId = 2, label = "Elsewhere")).rows()
        assertEquals(4, rows.size)
        assertEquals(1_479L, rows.first().balanceAfter.pence)
    }

    @Test
    fun `balances follow the overdraft zero point with the account's own limit`() {
        val rows = history(october, zeroPoint = ZeroPoint.OVERDRAFT_LIMIT).rows()
        // The £200 overdraft is added to every balance.
        assertEquals(listOf(21_479L, 50_000L, 70_000L, 20_000L), rows.map { it.balanceAfter.pence })
        assertEquals(Money(21_479), history(october, zeroPoint = ZeroPoint.OVERDRAFT_LIMIT).months.first().endBalance)
    }

    @Test
    fun `a row's balance reads Balance and the amount`() {
        assertEquals("Balance £14.79", history(october).rows().first().balanceText)
        val overdrawn = history(listOf(tx(-1_250, d(10, 1)))).rows().single()
        assertEquals("Balance -£12.50", overdrawn.balanceText)
    }

    // ---- grouping -------------------------------------------------------------------------------

    @Test
    fun `months, days and rows are newest first and days are labelled like Activity`() {
        val transactions = listOf(
            tx(100_000, d(8, 31), kind = TxKind.OPENING_BALANCE, createdAt = 1),
            tx(-100, d(10, 16), label = "Today one", createdAt = 1),
            tx(-200, d(10, 16), label = "Today two", createdAt = 2),
            tx(-300, d(10, 15), label = "Yesterday"),
            tx(-400, d(10, 9), label = "Earlier"),
            tx(-500, d(9, 12), label = "September"),
        )

        val months = history(transactions).months
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9), YearMonth.of(2026, 8)), months.map { it.month })

        val octDays = months.first().days
        assertEquals(listOf(d(10, 16), d(10, 15), d(10, 9)), octDays.map { it.date })
        assertEquals(listOf("Today", "Yesterday", "Fri 9 Oct"), octDays.map { it.label })
        assertEquals(listOf("Today two", "Today one"), octDays.first().rows.map { it.title })
        assertEquals("Sat 12 Sep", months[1].days.single().label)
    }

    @Test
    fun `month totals add up every row of the month`() {
        val transactions = listOf(
            tx(100_000, d(9, 1), kind = TxKind.OPENING_BALANCE),
            tx(-1_000, d(10, 1), categoryId = 1),
            tx(2_500, d(10, 2), categoryId = 2),
            tx(-3_000, d(10, 3), kind = TxKind.TRANSFER, group = "g"),
            tx(3_000, d(10, 3), accountId = 2, kind = TxKind.TRANSFER, group = "g"),
            tx(700, d(10, 4), kind = TxKind.TRANSFER, group = "h"),
            tx(-50, d(10, 5), categoryId = 3, kind = TxKind.RECONCILIATION),
        )
        val oct = history(transactions).months.first()
        assertEquals(Money(2_500 + 700), oct.moneyIn)
        assertEquals(Money(1_000 + 3_000 + 50), oct.moneyOut)
        assertEquals(Money(100_000 - 1_000 + 2_500 - 3_000 + 700 - 50), oct.endBalance)
    }

    @Test
    fun `an account with no transactions has no months`() {
        val none = history(emptyList())
        assertTrue(none.months.isEmpty())
        assertEquals(0, none.totalRows)
        assertEquals(0, none.shownRows)
    }

    // ---- badges and what a row opens ---------------------------------------------------------------------

    @Test
    fun `rows say what kind of entry they are`() {
        val transactions = listOf(
            tx(10_000, d(10, 1), kind = TxKind.OPENING_BALANCE, label = "Opening balance", importBatchId = 4),
            tx(-500, d(10, 2), categoryId = 1, label = "Coffee", importKey = "k", importBatchId = 4),
            tx(-1_500, d(10, 3), categoryId = 1, label = "Tesco · Groceries", receiptId = 6),
            tx(-2_000, d(10, 4), kind = TxKind.TRANSFER, group = "g", label = "To savings"),
            tx(2_000, d(10, 4), accountId = 2, kind = TxKind.TRANSFER, group = "g", label = "To savings"),
            tx(900, d(10, 5), kind = TxKind.TRANSFER, group = "h", label = "From savings"),
            tx(-700, d(10, 5), accountId = 2, kind = TxKind.TRANSFER, group = "h", label = "From savings"),
            tx(5_000, d(10, 6), kind = TxKind.TRANSFER, group = "lone", label = "Mum"),
            tx(-7_930, d(10, 7), categoryId = 3, kind = TxKind.RECONCILIATION, label = "Balance update"),
            tx(-100, d(10, 8), label = "Plain"),
        )
        val byTitle = history(transactions).rows().associateBy { it.title }

        // An opening balance moved by an import is not an imported row.
        assertEquals(listOf("Opening balance"), byTitle.getValue("Opening balance").badges)
        assertEquals(listOf("Imported"), byTitle.getValue("Coffee").badges)
        assertEquals(listOf("Receipt"), byTitle.getValue("Tesco · Groceries").badges)
        assertEquals(listOf("Transfer to Savings"), byTitle.getValue("To savings").badges)
        assertEquals(listOf("Transfer from Savings"), byTitle.getValue("From savings").badges)
        assertEquals(listOf("Transfer · other account not in the app"), byTitle.getValue("Mum").badges)
        assertEquals(listOf("Balance check"), byTitle.getValue("Balance update").badges)
        assertEquals(emptyList(), byTitle.getValue("Plain").badges)
    }

    @Test
    fun `an imported row can also be a receipt part or a transfer`() {
        val rows = history(
            listOf(
                tx(-500, d(10, 2), label = "Imported transfer", kind = TxKind.TRANSFER, group = "g", importKey = "k", importBatchId = 2),
                tx(500, d(10, 2), accountId = 2, label = "Imported transfer", kind = TxKind.TRANSFER, group = "g", importBatchId = 2),
            ),
        ).rows()
        assertEquals(listOf("Imported", "Transfer to Savings"), rows.single().badges)
    }

    @Test
    fun `titles and subtitles fall back sensibly`() {
        val rows = history(
            listOf(
                tx(-500, d(10, 2), categoryId = 1),
                tx(-500, d(10, 3)),
                tx(0, d(10, 4), kind = TxKind.OPENING_BALANCE),
                tx(-100, d(10, 5), categoryId = 3, kind = TxKind.RECONCILIATION),
                tx(-100, d(10, 6), kind = TxKind.TRANSFER, group = "x"),
            ),
        ).rows().asReversed()
        assertEquals(listOf("Groceries", "Untitled", "Opening balance", "Balance check", "Transfer"), rows.map { it.title })
        assertEquals(listOf("Groceries", null, null, "Untracked", null), rows.map { it.subtitle })
    }

    @Test
    fun `a transfer with both legs opens as one transfer, a lone leg as itself`() {
        val out = tx(-2_000, d(10, 4), kind = TxKind.TRANSFER, group = "g")
        val into = tx(2_000, d(10, 4), accountId = 2, kind = TxKind.TRANSFER, group = "g")
        val lone = tx(300, d(10, 5), kind = TxKind.TRANSFER, group = "lone")
        val plain = tx(-100, d(10, 6), label = "Plain")

        val byId = history(listOf(out, into, lone, plain)).rows().associateBy { it.tx.id }

        val transferRow = assertIs<ActivityRow.Transfer>(byId.getValue(out.id).editRow)
        assertEquals(out, transferRow.from)
        assertEquals(into, transferRow.to)
        assertEquals(ActivityRow.Single(lone), byId.getValue(lone.id).editRow)
        assertEquals(ActivityRow.Single(plain), byId.getValue(plain.id).editRow)
    }

    @Test
    fun `from the receiving account the same transfer still opens as the whole transfer`() {
        val out = tx(-2_000, d(10, 4), kind = TxKind.TRANSFER, group = "g")
        val into = tx(2_000, d(10, 4), accountId = 2, kind = TxKind.TRANSFER, group = "g")

        val row = history(listOf(out, into), account = savings).rows().single()

        assertEquals(listOf("Transfer from Lloyds current"), row.badges)
        val transferRow = assertIs<ActivityRow.Transfer>(row.editRow)
        assertEquals(out, transferRow.from)
        assertEquals(into, transferRow.to)
    }

    // ---- filters --------------------------------------------------------------------------------------

    private val mixed = listOf(
        tx(100_000, d(9, 1), kind = TxKind.OPENING_BALANCE, label = "Opening balance"),
        tx(-4_250, d(9, 20), categoryId = 1, label = "Tesco", note = "Weekly shop"),
        tx(-1_800, d(10, 3), categoryId = 1, label = "Lunch", note = "with Sam"),
        tx(12_000, d(10, 4), categoryId = 2, label = "Cafe shifts"),
        tx(-900, d(10, 10), label = "Bus", note = "tesco bag"),
    )

    @Test
    fun `search matches the label, the category name and the note, ignoring case`() {
        fun titles(search: String) = history(mixed, HistoryFilters(search = search)).rows().map { it.title }.toSet()

        assertEquals(setOf("Tesco", "Bus"), titles("TESCO"), "label and note")
        assertEquals(setOf("Tesco", "Lunch"), titles("grocer"), "category name")
        assertEquals(setOf("Lunch"), titles("sam"), "note")
        assertEquals(setOf("Cafe shifts"), titles(" cafe "), "trimmed")
        assertEquals(emptySet(), titles("zzz"))
        assertEquals(5, history(mixed, HistoryFilters(search = "  ")).shownRows, "a blank search shows everything")
    }

    @Test
    fun `the category filter keeps only that category`() {
        val titles = history(mixed, HistoryFilters(categoryId = 1)).rows().map { it.title }
        assertEquals(listOf("Lunch", "Tesco"), titles)
        assertEquals(emptyList(), history(mixed, HistoryFilters(categoryId = 3)).rows())
    }

    @Test
    fun `in and out keep money in or money out, all keeps everything`() {
        fun titles(direction: DirectionFilter) = history(mixed, HistoryFilters(direction = direction)).rows().map { it.title }.toSet()

        assertEquals(setOf("Cafe shifts", "Opening balance"), titles(DirectionFilter.IN))
        assertEquals(setOf("Tesco", "Lunch", "Bus"), titles(DirectionFilter.OUT))
        assertEquals(5, titles(DirectionFilter.ALL).size)
    }

    @Test
    fun `filters combine`() {
        val titles = history(mixed, HistoryFilters(search = "tesco", direction = DirectionFilter.OUT, categoryId = 1)).rows().map { it.title }
        assertEquals(listOf("Tesco"), titles)
    }

    @Test
    fun `filters hide rows but never change balances or month totals`() {
        val all = history(mixed)
        val filtered = history(mixed, HistoryFilters(categoryId = 1))

        val balanceOf = all.rows().associate { it.tx.id to it.balanceAfter }
        assertTrue(filtered.rows().isNotEmpty())
        filtered.rows().forEach { assertEquals(balanceOf.getValue(it.tx.id), it.balanceAfter) }

        val octAll = all.months.first()
        val octFiltered = filtered.months.first()
        assertEquals(octAll.moneyIn, octFiltered.moneyIn)
        assertEquals(octAll.moneyOut, octFiltered.moneyOut)
        assertEquals(octAll.endBalance, octFiltered.endBalance)
        assertEquals(Money(12_000), octFiltered.moneyIn, "the wages row is hidden but still counted")
        assertEquals(1, octFiltered.days.sumOf { it.rows.size })
    }

    @Test
    fun `a month with no visible rows is left out and the counts say how many are shown`() {
        val filtered = history(mixed, HistoryFilters(search = "tesco", direction = DirectionFilter.OUT, categoryId = 1))
        assertEquals(listOf(YearMonth.of(2026, 9)), filtered.months.map { it.month })
        assertEquals(5, filtered.totalRows)
        assertEquals(1, filtered.shownRows)

        val none = history(mixed, HistoryFilters(search = "zzz"))
        assertTrue(none.months.isEmpty())
        assertEquals(5, none.totalRows)
        assertEquals(0, none.shownRows)
    }

    @Test
    fun `filters know when they are the default`() {
        assertTrue(HistoryFilters().isDefault)
        assertTrue(HistoryFilters(search = "  ").isDefault)
        assertTrue(!HistoryFilters(search = "a").isDefault)
        assertTrue(!HistoryFilters(categoryId = 1).isDefault)
        assertTrue(!HistoryFilters(direction = DirectionFilter.IN).isDefault)
    }

    // ---- ranges and the summary ---------------------------------------------------------------------------

    @Test
    fun `ranges start a month, three months, six months or a year back`() {
        assertEquals(d(9, 16), HistoryRange.MONTH_1.startDate(today, null))
        assertEquals(d(7, 16), HistoryRange.MONTHS_3.startDate(today, null))
        assertEquals(d(4, 16), HistoryRange.MONTHS_6.startDate(today, null))
        assertEquals(d(10, 16, 2025), HistoryRange.YEAR_1.startDate(today, null))
        assertEquals(listOf("1M", "3M", "6M", "1Y", "All"), HistoryRange.entries.map { it.label })
    }

    @Test
    fun `all starts at the first transaction, or today when there is none`() {
        assertEquals(d(2, 3, 2025), HistoryRange.ALL.startDate(today, d(2, 3, 2025)))
        assertEquals(today, HistoryRange.ALL.startDate(today, null))
        assertEquals(today, HistoryRange.ALL.startDate(today, today.plusDays(3)), "a future-dated first row doesn't start the range after today")
    }

    @Test
    fun `earliest date and order only look at the account`() {
        val transactions = october + tx(1, d(1, 1), accountId = 2)
        assertEquals(d(9, 30), AccountHistoryMapping.earliestDate(1, transactions))
        assertEquals(d(1, 1), AccountHistoryMapping.earliestDate(2, transactions))
        assertNull(AccountHistoryMapping.earliestDate(3, transactions))
        assertEquals(listOf(d(9, 30), d(10, 2), d(10, 5), d(10, 9)), AccountHistoryMapping.ordered(1, transactions.reversed()).map { it.date })
    }

    private fun summary(
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate = today,
        zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
    ) = AccountHistoryMapping.summary(current, transactions, zeroPoint, from, to)

    @Test
    fun `the summary is the balance at the start and end, and the money in and out between`() {
        val transactions = listOf(
            tx(100_000, d(7, 1), kind = TxKind.OPENING_BALANCE),
            tx(-10_000, d(8, 1), label = "Before the range"),
            tx(20_000, d(9, 20), label = "Wages"),
            tx(-5_000, d(10, 1), label = "Rent"),
            tx(-1_500, d(10, 10), label = "Food"),
            tx(-9_999, d(10, 17), label = "After today"),
        )

        val summary = summary(transactions, from = d(9, 16))

        assertEquals(d(9, 16), summary.from)
        assertEquals(today, summary.to)
        assertEquals(Money(90_000), summary.startBalance)
        assertEquals(Money(103_500), summary.endBalance)
        assertEquals(Money(20_000), summary.moneyIn)
        assertEquals(Money(6_500), summary.moneyOut)
        assertEquals(summary.endBalance, summary.startBalance + summary.moneyIn - summary.moneyOut)
    }

    @Test
    fun `an opening balance inside the range is part of the starting position`() {
        val transactions = listOf(
            tx(50_000, d(10, 1), kind = TxKind.OPENING_BALANCE),
            tx(-2_000, d(10, 3)),
            tx(1_000, d(10, 4)),
        )

        val summary = summary(transactions, from = d(9, 16))

        assertEquals(Money(50_000), summary.startBalance)
        assertEquals(Money(1_000), summary.moneyIn)
        assertEquals(Money(2_000), summary.moneyOut)
        assertEquals(Money(49_000), summary.endBalance)
        assertEquals(summary.endBalance, summary.startBalance + summary.moneyIn - summary.moneyOut)
    }

    @Test
    fun `the summary counts transfers and balance checks as money in and out`() {
        val transactions = listOf(
            tx(10_000, d(9, 1), kind = TxKind.OPENING_BALANCE),
            tx(-3_000, d(10, 3), kind = TxKind.TRANSFER, group = "g"),
            tx(700, d(10, 4), kind = TxKind.TRANSFER, group = "h"),
            tx(-50, d(10, 5), categoryId = 3, kind = TxKind.RECONCILIATION),
        )
        val summary = summary(transactions, from = d(10, 1))
        assertEquals(Money(700), summary.moneyIn)
        assertEquals(Money(3_050), summary.moneyOut)
        assertEquals(Money(7_650), summary.endBalance)
    }

    @Test
    fun `the summary uses the zero point and the account's overdraft`() {
        val summary = summary(listOf(tx(10_000, d(9, 1), kind = TxKind.OPENING_BALANCE), tx(-2_500, d(10, 3))), from = d(10, 1), zeroPoint = ZeroPoint.OVERDRAFT_LIMIT)
        assertEquals(Money(30_000), summary.startBalance)
        assertEquals(Money(27_500), summary.endBalance)
    }

    @Test
    fun `a range with nothing in it keeps one balance throughout`() {
        val summary = summary(october, from = d(10, 10), to = d(10, 12))
        assertEquals(Money(1_479), summary.startBalance)
        assertEquals(Money(1_479), summary.endBalance)
        assertEquals(Money.ZERO, summary.moneyIn)
        assertEquals(Money.ZERO, summary.moneyOut)
    }
}
