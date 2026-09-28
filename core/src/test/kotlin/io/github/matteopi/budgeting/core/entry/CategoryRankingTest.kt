package io.github.matteopi.budgeting.core.entry

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryRankingTest {

    private val groceries = Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
    private val eatingOut = Category(id = 2, name = "Eating out", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
    private val transport = Category(id = 3, name = "Transport", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, sortOrder = 1)
    private val shopping = Category(id = 4, name = "Shopping", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, sortOrder = 2)
    private val wages = Category(id = 5, name = "Wages", flow = Flow.IN, forecastMode = ForecastMode.LEARNED)
    private val archived = Category(id = 6, name = "Old", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, archived = true)
    private val untracked = Category(id = 7, name = "Untracked", flow = Flow.BOTH, forecastMode = ForecastMode.LEARNED, isSystem = true)

    private val today = LocalDate.of(2026, 9, 28)

    private fun tx(categoryId: Long, daysAgo: Long, amount: Money = Money.pounds(-10)) = Transaction(
        accountId = 1,
        amount = amount,
        date = today.minusDays(daysAgo),
        categoryId = categoryId,
    )

    @Test
    fun `ranks by usage count within the window, most used first`() {
        val transactions = listOf(
            tx(transport.id, 1), tx(transport.id, 2), tx(transport.id, 3),
            tx(groceries.id, 1), tx(groceries.id, 2),
            tx(shopping.id, 1),
        )
        val ranked = CategoryRanking.rank(
            listOf(groceries, eatingOut, transport, shopping), transactions, Flow.OUT, today,
        )
        assertEquals(listOf(transport, groceries, shopping, eatingOut), ranked)
    }

    @Test
    fun `ties break by sortOrder`() {
        val ranked = CategoryRanking.rank(listOf(shopping, transport), emptyList(), Flow.OUT, today)
        assertEquals(listOf(transport, shopping), ranked)
    }

    @Test
    fun `excludes archived and system categories, and the wrong flow`() {
        val ranked = CategoryRanking.rank(
            listOf(groceries, wages, archived, untracked), emptyList(), Flow.OUT, today,
        )
        assertEquals(listOf(groceries), ranked)
    }

    @Test
    fun `transactions outside the window do not count`() {
        val transactions = listOf(tx(groceries.id, 61), tx(transport.id, 5))
        val ranked = CategoryRanking.rank(
            listOf(groceries, transport), transactions, Flow.OUT, today, windowDays = 60,
        )
        assertEquals(listOf(transport, groceries), ranked)
    }

    @Test
    fun `only NORMAL transactions count`() {
        val nonNormal = Transaction(
            accountId = 1, amount = Money.pounds(-5), date = today, categoryId = groceries.id, kind = TxKind.RECONCILIATION,
        )
        val ranked = CategoryRanking.rank(listOf(groceries, transport), listOf(nonNormal), Flow.OUT, today)
        assertEquals(listOf(groceries, transport), ranked) // tie -> sortOrder (groceries=0, transport=1)
    }
}
