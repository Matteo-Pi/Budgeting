package io.github.matteopi.budgeting.ui.activity

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActivityGroupingTest {

    private val today = LocalDate.of(2026, 9, 28) // Monday

    private fun tx(
        id: Long,
        accountId: Long = 1,
        amount: Money = Money(-500),
        date: LocalDate = today,
        categoryId: Long? = 1,
        label: String = "",
        kind: TxKind = TxKind.NORMAL,
        transferGroupId: String? = null,
        createdAt: Long = id,
    ) = Transaction(
        id = id,
        accountId = accountId,
        amount = amount,
        date = date,
        createdAtEpochMillis = createdAt,
        categoryId = categoryId,
        label = label,
        kind = kind,
        transferGroupId = transferGroupId,
    )

    @Test
    fun `pairs transfer legs into one row`() {
        val out = tx(1, accountId = 1, amount = Money(-1000), categoryId = null, kind = TxKind.TRANSFER, transferGroupId = "g1", label = "Move")
        val into = tx(2, accountId = 2, amount = Money(1000), categoryId = null, kind = TxKind.TRANSFER, transferGroupId = "g1", label = "Move")
        val normal = tx(3, amount = Money(-320), label = "Tesco")

        val rows = ActivityGrouping.buildRows(listOf(out, into, normal))

        assertEquals(2, rows.size)
        val transferRow = rows.filterIsInstance<ActivityRow.Transfer>().single()
        assertEquals(1L, transferRow.from.accountId)
        assertEquals(2L, transferRow.to.accountId)
        assertTrue(rows.any { it is ActivityRow.Single && it.tx.id == 3L })
    }

    @Test
    fun `sorts rows by date then recency`() {
        val older = tx(1, date = today.minusDays(1), createdAt = 100)
        val newerSameDay = tx(2, date = today, createdAt = 50)
        val newest = tx(3, date = today, createdAt = 200)

        val rows = ActivityGrouping.buildRows(listOf(older, newerSameDay, newest))

        assertEquals(listOf(3L, 2L, 1L), rows.map { (it as ActivityRow.Single).tx.id })
    }

    @Test
    fun `day labels are Today, Yesterday, then weekday date`() {
        assertEquals("Today", ActivityGrouping.dayLabel(today, today))
        assertEquals("Yesterday", ActivityGrouping.dayLabel(today.minusDays(1), today))
        assertEquals("Mon 28 Sep", ActivityGrouping.dayLabel(today, today.plusDays(3)))
    }

    @Test
    fun `groupByDay buckets rows under one header per day`() {
        val a = tx(1, date = today)
        val b = tx(2, date = today, createdAt = 2)
        val c = tx(3, date = today.minusDays(1))
        val rows = ActivityGrouping.buildRows(listOf(a, b, c))

        val groups = ActivityGrouping.groupByDay(rows, today)

        assertEquals(2, groups.size)
        assertEquals("Today", groups[0].label)
        assertEquals(2, groups[0].rows.size)
        assertEquals("Yesterday", groups[1].label)
        assertEquals(1, groups[1].rows.size)
    }

    @Test
    fun `filters by account, category, direction and label search`() {
        val categories = mapOf(1L to Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED))
        val groceries = tx(1, accountId = 10, categoryId = 1, amount = Money(-500), label = "Tesco")
        val income = tx(2, accountId = 10, categoryId = 2, amount = Money(2000), label = "Wages")
        val otherAccount = tx(3, accountId = 20, categoryId = 1, amount = Money(-100), label = "Coffee")

        val rows = ActivityGrouping.buildRows(listOf(groceries, income, otherAccount)).map { it as ActivityRow.Single }

        assertEquals(
            setOf(1L, 2L),
            rows.filter { ActivityGrouping.matches(it, ActivityFilters(accountId = 10), categories) }.map { it.tx.id }.toSet(),
        )
        assertEquals(
            setOf(2L),
            rows.filter { ActivityGrouping.matches(it, ActivityFilters(direction = DirectionFilter.IN), categories) }.map { it.tx.id }.toSet(),
        )
        assertEquals(
            setOf(1L, 3L),
            rows.filter { ActivityGrouping.matches(it, ActivityFilters(categoryId = 1), categories) }.map { it.tx.id }.toSet(),
        )
        assertEquals(
            setOf(1L),
            rows.filter { ActivityGrouping.matches(it, ActivityFilters(search = "tesc"), categories) }.map { it.tx.id }.toSet(),
        )
    }

    @Test
    fun `direction filter excludes transfer rows`() {
        val out = tx(1, accountId = 1, amount = Money(-1000), categoryId = null, kind = TxKind.TRANSFER, transferGroupId = "g1")
        val into = tx(2, accountId = 2, amount = Money(1000), categoryId = null, kind = TxKind.TRANSFER, transferGroupId = "g1")
        val row = ActivityGrouping.buildRows(listOf(out, into)).single()
        assertIs<ActivityRow.Transfer>(row)

        assertTrue(!ActivityGrouping.matches(row, ActivityFilters(direction = DirectionFilter.IN), emptyMap()))
        assertTrue(ActivityGrouping.matches(row, ActivityFilters(), emptyMap()))
    }
}
