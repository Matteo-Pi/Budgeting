package io.github.matteopi.budgeting.core.reconcile

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate

/** The result of planning an "update balance" action, ready for a repository to persist. */
data class ReconcilePlan(
    val difference: Money,
    /** Null when [difference] is zero (nothing to record). */
    val transaction: Transaction?,
    val reconciliation: Reconciliation,
)

/** See docs/PLAN.md §5.15. */
object Reconcile {

    fun plan(
        accountId: Long,
        expected: Money,
        actual: Money,
        today: LocalDate,
        previous: Reconciliation?,
        accountStartDate: LocalDate?,
        untrackedCategoryId: Long,
        nowMillis: Long,
    ): ReconcilePlan {
        val difference = actual - expected

        val transaction = if (difference.isZero) {
            null
        } else {
            Transaction(
                accountId = accountId,
                amount = difference,
                date = today,
                createdAtEpochMillis = nowMillis,
                categoryId = untrackedCategoryId,
                label = "Balance update",
                kind = TxKind.RECONCILIATION,
            )
        }

        var periodStart = previous?.date?.plusDays(1)
            ?: accountStartDate?.plusDays(1)
            ?: today.minusDays(6)
        if (periodStart.isAfter(today)) periodStart = today

        val reconciliation = Reconciliation(
            accountId = accountId,
            date = today,
            expected = expected,
            actual = actual,
            transactionId = null,
            periodStart = periodStart,
        )

        return ReconcilePlan(difference, transaction, reconciliation)
    }
}
