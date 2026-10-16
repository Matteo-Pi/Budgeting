package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferEditsTest {

    private val day = LocalDate.of(2026, 10, 5)
    private val newDay = LocalDate.of(2026, 10, 2)

    private fun leg(
        id: Long,
        accountId: Long,
        pence: Long,
        group: String? = "g1",
        label: String = "Top up",
        note: String = "",
        importKey: String? = null,
        importBatchId: Long? = null,
    ) = Transaction(
        id = id,
        accountId = accountId,
        amount = Money(pence),
        date = day,
        createdAtEpochMillis = 100,
        categoryId = null,
        label = label,
        note = note,
        kind = TxKind.TRANSFER,
        transferGroupId = group,
        importBatchId = importBatchId,
        importKey = importKey,
    )

    private val out = leg(1, accountId = 10, pence = -5_000, note = "mine", importKey = "key-a", importBatchId = 7)
    private val into = leg(2, accountId = 20, pence = 5_000, importBatchId = 7)

    // ---- sides ----------------------------------------------------------------------------------

    @Test
    fun `two legs pair as outgoing and incoming whatever their order`() {
        assertEquals(TransferEdits.Sides(out, into), TransferEdits.sides(listOf(out, into)))
        assertEquals(TransferEdits.Sides(out, into), TransferEdits.sides(listOf(into, out)))
    }

    @Test
    fun `a lone leg is outgoing when negative and incoming otherwise`() {
        assertEquals(TransferEdits.Sides(out, null), TransferEdits.sides(listOf(out)))
        assertEquals(TransferEdits.Sides(null, into), TransferEdits.sides(listOf(into)))
        assertEquals(TransferEdits.Sides(null, null), TransferEdits.sides(emptyList()))
    }

    @Test
    fun `legs lists the sides that exist, outgoing first`() {
        assertEquals(listOf(out, into), TransferEdits.sides(listOf(into, out)).legs)
        assertEquals(listOf(into), TransferEdits.sides(listOf(into)).legs)
    }

    // ---- toTransfer -------------------------------------------------------------------------------

    private val spend = Transaction(
        id = 5,
        accountId = 10,
        amount = Money(-2_460),
        date = day,
        createdAtEpochMillis = 50,
        categoryId = 3,
        label = "Tesco",
        note = "weekly shop",
        kind = TxKind.NORMAL,
        recurringRuleId = 4,
        importBatchId = 9,
        importKey = "imported-key",
    )

    @Test
    fun `a normal transaction becomes a transfer leg with no category and a group id`() {
        val legs = TransferEdits.toTransfer(spend, otherAccountId = null, groupId = "new-group", nowMillis = 999)

        val own = legs.single()
        assertEquals(5L, own.id)
        assertEquals(TxKind.TRANSFER, own.kind)
        assertNull(own.categoryId)
        assertEquals("new-group", own.transferGroupId)
        // Everything else about the row is untouched.
        assertEquals(Money(-2_460), own.amount)
        assertEquals("Tesco", own.label)
        assertEquals("weekly shop", own.note)
        assertEquals("imported-key", own.importKey)
        assertEquals(9L, own.importBatchId)
    }

    @Test
    fun `choosing another account adds the opposite leg there`() {
        val legs = TransferEdits.toTransfer(spend, otherAccountId = 20, groupId = "new-group", nowMillis = 999)

        assertEquals(2, legs.size)
        val opposite = legs[1]
        assertEquals(0L, opposite.id)
        assertEquals(20L, opposite.accountId)
        assertEquals(Money(2_460), opposite.amount, "the amount is negated")
        assertEquals(day, opposite.date)
        assertEquals("Tesco", opposite.label)
        assertEquals("new-group", opposite.transferGroupId)
        assertEquals(TxKind.TRANSFER, opposite.kind)
        assertNull(opposite.categoryId)
        assertEquals(999L, opposite.createdAtEpochMillis)
        // Not a row of the other account's statement, and not part of anything the original belonged to.
        assertNull(opposite.importKey)
        assertNull(opposite.recurringRuleId)
        assertEquals("", opposite.note)
        // The import batch is kept, so undoing the import removes both legs.
        assertEquals(9L, opposite.importBatchId)
        assertEquals(0, legs.sumOf { it.amount.pence }.toInt(), "a transfer nets to nothing")
    }

    @Test
    fun `an income becomes the incoming leg and the other account gets the outgoing one`() {
        val wages = spend.copy(amount = Money(10_000))
        val legs = TransferEdits.toTransfer(wages, otherAccountId = 20, groupId = "g", nowMillis = 1)
        assertEquals(Money(10_000), legs[0].amount)
        assertEquals(Money(-10_000), legs[1].amount)
    }

    @Test
    fun `the other account can't be the transaction's own account`() {
        val legs = TransferEdits.toTransfer(spend, otherAccountId = 10, groupId = "g", nowMillis = 1)
        assertEquals(1, legs.size)
    }

    // ---- plan -------------------------------------------------------------------------------------

    private fun plan(
        legs: List<Transaction>,
        from: Long?,
        to: Long?,
        amount: Money = Money(5_000),
        label: String = "Top up",
    ) = TransferEdits.plan(legs, from, to, amount, newDay, label, groupId = "g1", nowMillis = 777)

    @Test
    fun `editing the amount, date and label updates both legs in place`() {
        val plan = plan(listOf(out, into), from = 10, to = 20, amount = Money(-6_000), label = "Rent share")

        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
        val updatedOut = plan.updates.single { it.id == 1L }
        val updatedIn = plan.updates.single { it.id == 2L }
        assertEquals(Money(-6_000), updatedOut.amount)
        assertEquals(Money(6_000), updatedIn.amount, "the amount is taken as a magnitude")
        assertEquals(newDay, updatedOut.date)
        assertEquals(newDay, updatedIn.date)
        assertEquals("Rent share", updatedOut.label)
        assertEquals("Rent share", updatedIn.label)
        // Notes and import keys stay with their rows.
        assertEquals("mine", updatedOut.note)
        assertEquals("key-a", updatedOut.importKey)
    }

    @Test
    fun `pointing a side at a different account moves that leg`() {
        val plan = plan(listOf(out, into), from = 10, to = 30)

        assertEquals(30L, plan.updates.single { it.id == 2L }.accountId)
        assertEquals(10L, plan.updates.single { it.id == 1L }.accountId)
        assertTrue(plan.inserts.isEmpty() && plan.deletes.isEmpty())
    }

    @Test
    fun `not in the app removes that side's leg`() {
        val plan = plan(listOf(out, into), from = 10, to = null)

        assertEquals(listOf(2L), plan.deletes)
        assertEquals(listOf(1L), plan.updates.map { it.id })
        assertTrue(plan.inserts.isEmpty())
    }

    @Test
    fun `a lone leg gains its missing side`() {
        val plan = plan(listOf(out), from = 10, to = 20)

        assertEquals(listOf(1L), plan.updates.map { it.id })
        val added = plan.inserts.single()
        assertEquals(0L, added.id)
        assertEquals(20L, added.accountId)
        assertEquals(Money(5_000), added.amount)
        assertEquals(newDay, added.date)
        assertEquals("g1", added.transferGroupId)
        assertEquals(777L, added.createdAtEpochMillis)
        assertNull(added.importKey, "a new leg has no statement row")
        assertEquals("", added.note)
        assertEquals(7L, added.importBatchId, "it keeps the batch of the leg it was modelled on")
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `a lone incoming leg gains an outgoing side`() {
        val plan = plan(listOf(into), from = 10, to = 20)

        assertEquals(listOf(2L), plan.updates.map { it.id })
        val added = plan.inserts.single()
        assertEquals(10L, added.accountId)
        assertEquals(Money(-5_000), added.amount)
    }

    @Test
    fun `a leg without a group id joins the new group`() {
        val ungrouped = out.copy(transferGroupId = null)
        val plan = plan(listOf(ungrouped), from = 10, to = 20)

        assertEquals("g1", plan.updates.single().transferGroupId)
        assertEquals("g1", plan.inserts.single().transferGroupId)
    }

    @Test
    fun `swapping the direction swaps which leg is negative`() {
        val plan = plan(listOf(out, into), from = 20, to = 10)

        val first = plan.updates.single { it.id == 1L }
        val second = plan.updates.single { it.id == 2L }
        assertEquals(20L, first.accountId)
        assertEquals(Money(-5_000), first.amount)
        assertEquals(10L, second.accountId)
        assertEquals(Money(5_000), second.amount)
    }

    @Test
    fun `a transfer needs an account in the app and two different ones`() {
        assertFailsWith<IllegalArgumentException> { plan(listOf(out, into), from = null, to = null) }
        assertFailsWith<IllegalArgumentException> { plan(listOf(out, into), from = 10, to = 10) }
    }

    // ---- toNormal ---------------------------------------------------------------------------------

    @Test
    fun `not a transfer keeps the leg as a normal transaction in the chosen category`() {
        val normal = TransferEdits.toNormal(out, categoryId = 3)

        assertEquals(TxKind.NORMAL, normal.kind)
        assertEquals(3L, normal.categoryId)
        assertNull(normal.transferGroupId)
        assertEquals(10L, normal.accountId)
        assertEquals(Money(-5_000), normal.amount)
        assertEquals("Top up", normal.label)
        assertEquals("key-a", normal.importKey)
        assertEquals(1L, normal.id)
    }
}
