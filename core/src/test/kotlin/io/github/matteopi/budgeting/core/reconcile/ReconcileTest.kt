package io.github.matteopi.budgeting.core.reconcile

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReconcileTest {

    private val today = LocalDate.of(2026, 9, 28)
    private val untrackedId = 99L

    @Test
    fun `zero difference creates no transaction`() {
        val plan = Reconcile.plan(
            accountId = 1,
            expected = Money.pounds(100),
            actual = Money.pounds(100),
            today = today,
            previous = null,
            accountStartDate = today.minusDays(30),
            untrackedCategoryId = untrackedId,
            nowMillis = 1_000L,
        )
        assertEquals(Money.ZERO, plan.difference)
        assertNull(plan.transaction)
        assertEquals(today, plan.reconciliation.date)
    }

    @Test
    fun `positive difference records untracked income`() {
        val plan = Reconcile.plan(
            accountId = 1,
            expected = Money.pounds(100),
            actual = Money.pounds(120),
            today = today,
            previous = null,
            accountStartDate = today.minusDays(30),
            untrackedCategoryId = untrackedId,
            nowMillis = 1_000L,
        )
        assertEquals(Money.pounds(20), plan.difference)
        val tx = assertNotNull(plan.transaction)
        assertEquals(Money.pounds(20), tx.amount)
        assertEquals(untrackedId, tx.categoryId)
        assertEquals(TxKind.RECONCILIATION, tx.kind)
        assertEquals("Balance update", tx.label)
        assertEquals(today, tx.date)
        assertEquals(1_000L, tx.createdAtEpochMillis)
        assertEquals(1L, tx.accountId)
    }

    @Test
    fun `negative difference records untracked spending`() {
        val plan = Reconcile.plan(
            accountId = 1,
            expected = Money.pounds(100),
            actual = Money.pounds(80),
            today = today,
            previous = null,
            accountStartDate = today.minusDays(30),
            untrackedCategoryId = untrackedId,
            nowMillis = 1_000L,
        )
        assertEquals(Money.pounds(-20), plan.difference)
        assertEquals(Money.pounds(-20), plan.transaction?.amount)
    }

    @Test
    fun `periodStart is the day after the previous reconciliation`() {
        val previous = Reconciliation(
            accountId = 1, date = today.minusDays(10), expected = Money.pounds(50), actual = Money.pounds(50),
            periodStart = today.minusDays(20),
        )
        val plan = Reconcile.plan(
            accountId = 1, expected = Money.pounds(100), actual = Money.pounds(100), today = today,
            previous = previous, accountStartDate = today.minusDays(30), untrackedCategoryId = untrackedId,
            nowMillis = 0L,
        )
        assertEquals(today.minusDays(9), plan.reconciliation.periodStart)
    }

    @Test
    fun `periodStart falls back to the day after the account start date`() {
        val plan = Reconcile.plan(
            accountId = 1, expected = Money.pounds(100), actual = Money.pounds(100), today = today,
            previous = null, accountStartDate = today.minusDays(3), untrackedCategoryId = untrackedId,
            nowMillis = 0L,
        )
        assertEquals(today.minusDays(2), plan.reconciliation.periodStart)
    }

    @Test
    fun `periodStart falls back to seven days ending today when nothing else is known`() {
        val plan = Reconcile.plan(
            accountId = 1, expected = Money.pounds(100), actual = Money.pounds(100), today = today,
            previous = null, accountStartDate = null, untrackedCategoryId = untrackedId, nowMillis = 0L,
        )
        assertEquals(today.minusDays(6), plan.reconciliation.periodStart)
    }

    @Test
    fun `periodStart never lands after today`() {
        val previous = Reconciliation(
            accountId = 1, date = today, expected = Money.ZERO, actual = Money.ZERO, periodStart = today.minusDays(6),
        )
        val plan = Reconcile.plan(
            accountId = 1, expected = Money.pounds(100), actual = Money.pounds(100), today = today,
            previous = previous, accountStartDate = null, untrackedCategoryId = untrackedId, nowMillis = 0L,
        )
        assertEquals(today, plan.reconciliation.periodStart)
    }

    @Test
    fun `reconciliation records expected and actual raw balances`() {
        val plan = Reconcile.plan(
            accountId = 1, expected = Money.pounds(100), actual = Money.pounds(93), today = today,
            previous = null, accountStartDate = null, untrackedCategoryId = untrackedId, nowMillis = 0L,
        )
        assertEquals(Money.pounds(100), plan.reconciliation.expected)
        assertEquals(Money.pounds(93), plan.reconciliation.actual)
        assertEquals(1L, plan.reconciliation.accountId)
    }
}
