package io.github.matteopi.budgeting.ui.plan

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectionTest {
    @Test
    fun signedAmountAppliesSignConvention() {
        val magnitude = Money.pounds(50)
        assertEquals(Money.pounds(-50), Direction.OUT.signedAmount(magnitude))
        assertEquals(Money.pounds(50), Direction.IN.signedAmount(magnitude))
        assertEquals(Money.pounds(50), Direction.TRANSFER.signedAmount(magnitude))
    }

    @Test
    fun signedAmountAlwaysUsesTheMagnitude() {
        // Even if a caller passes an already-negative amount, the sign comes from the direction.
        val negative = Money.pounds(-50)
        assertEquals(Money.pounds(50), Direction.IN.signedAmount(negative))
        assertEquals(Money.pounds(-50), Direction.OUT.signedAmount(negative))
    }

    @Test
    fun directionOfDetectsTransferFirst() {
        assertEquals(Direction.TRANSFER, directionOf(Money.pounds(50), toAccountId = 2))
        assertEquals(Direction.TRANSFER, directionOf(Money.pounds(-50), toAccountId = 2))
    }

    @Test
    fun directionOfFallsBackToSign() {
        assertEquals(Direction.OUT, directionOf(Money.pounds(-10), toAccountId = null))
        assertEquals(Direction.IN, directionOf(Money.pounds(10), toAccountId = null))
        assertEquals(Direction.IN, directionOf(Money.ZERO, toAccountId = null))
    }

    @Test
    fun defaultPostingModeByDirection() {
        assertEquals(PostingMode.AUTO, Direction.OUT.defaultPostingMode())
        assertEquals(PostingMode.AUTO, Direction.TRANSFER.defaultPostingMode())
        assertEquals(PostingMode.CONFIRM, Direction.IN.defaultPostingMode())
    }
}
