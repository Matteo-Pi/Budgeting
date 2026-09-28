package io.github.matteopi.budgeting.ui.home

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Reconciliation
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconcileNudgeTest {

    private val today = LocalDate.of(2026, 9, 28)

    private fun reconciliation(accountId: Long, date: LocalDate) = Reconciliation(
        accountId = accountId,
        date = date,
        expected = Money.pounds(50),
        actual = Money.pounds(50),
        periodStart = date.minusDays(6),
    )

    @Test
    fun `stale when there is no reconciliation at all`() {
        assertTrue(ReconcileNudge.isStale(emptyList(), setOf(1L), today))
    }

    @Test
    fun `not stale when a pool account was reconciled within 7 days`() {
        val reconciliations = listOf(reconciliation(1L, today.minusDays(3)))
        assertFalse(ReconcileNudge.isStale(reconciliations, setOf(1L), today))
    }

    @Test
    fun `not stale exactly 7 days ago`() {
        val reconciliations = listOf(reconciliation(1L, today.minusDays(7)))
        assertFalse(ReconcileNudge.isStale(reconciliations, setOf(1L), today))
    }

    @Test
    fun `stale when the most recent reconciliation is older than 7 days`() {
        val reconciliations = listOf(reconciliation(1L, today.minusDays(8)))
        assertTrue(ReconcileNudge.isStale(reconciliations, setOf(1L), today))
    }

    @Test
    fun `only pool accounts count`() {
        val reconciliations = listOf(reconciliation(99L, today))
        assertTrue(ReconcileNudge.isStale(reconciliations, setOf(1L), today))
    }

    @Test
    fun `no nudge when there is no pool account`() {
        val reconciliations = listOf(reconciliation(1L, today.minusDays(30)))
        assertFalse(ReconcileNudge.isStale(reconciliations, emptySet(), today))
    }

    @Test
    fun `uses the most recent reconciliation across all pool accounts`() {
        val reconciliations = listOf(
            reconciliation(1L, today.minusDays(20)),
            reconciliation(2L, today.minusDays(2)),
        )
        assertFalse(ReconcileNudge.isStale(reconciliations, setOf(1L, 2L), today))
    }
}
