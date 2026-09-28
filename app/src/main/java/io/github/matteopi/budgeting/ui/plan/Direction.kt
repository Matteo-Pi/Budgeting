package io.github.matteopi.budgeting.ui.plan

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode

/**
 * The direction of a recurring rule or scheduled item, derived from its sign convention
 * (docs/PLAN.md §5.1): money out is negative, money in is positive, and a transfer is a
 * positive amount moved into [io.github.matteopi.budgeting.core.model.RecurringRule.toAccountId].
 */
enum class Direction { OUT, IN, TRANSFER }

/** Applies this direction's sign convention to a positive [magnitude]. */
fun Direction.signedAmount(magnitude: Money): Money = when (this) {
    Direction.OUT -> -magnitude.abs()
    Direction.IN, Direction.TRANSFER -> magnitude.abs()
}

/** The [Direction] implied by a stored signed [amount] and optional [toAccountId]. */
fun directionOf(amount: Money, toAccountId: Long?): Direction = when {
    toAccountId != null -> Direction.TRANSFER
    amount.isNegative -> Direction.OUT
    else -> Direction.IN
}

/** Posting mode default by direction: AUTO for money going out (direct debits/transfers), CONFIRM for incomes. */
fun Direction.defaultPostingMode(): PostingMode = when (this) {
    Direction.OUT, Direction.TRANSFER -> PostingMode.AUTO
    Direction.IN -> PostingMode.CONFIRM
}
