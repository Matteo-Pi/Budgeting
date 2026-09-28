package io.github.matteopi.budgeting.core.analysis

import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.math.abs

/** Per-horizon error/coverage numbers for the pool-balance forecast. */
data class HorizonAccuracy(
    val days: Int,
    val evaluated: Int,
    val meanAbsErrorTier1: Money,
    val meanAbsErrorFull: Money,
    /** Share of actuals that fell inside the full model's P10-P90 band. Null if never measurable. */
    val coverage: Double?,
)

/** Per-category week-ahead error, tier 1 vs the full model. */
data class CategoryAccuracy(
    val categoryId: Long,
    val evaluated: Int,
    val meanAbsErrorTier1: Money,
    val meanAbsErrorFull: Money,
)

data class AccuracyReport(
    val horizons: List<HorizonAccuracy>,
    val categories: List<CategoryAccuracy>,
    /** How much better (positive) or worse (negative) the full model is vs. tier 1, in percent. */
    val improvementPercent: Double?,
)

/**
 * Records predictions as snapshots and later scores them against what actually happened.
 * Written against the public [ForecastResult] contract only (see docs/PLAN.md §5.14).
 */
object Accuracy {

    private val HORIZONS = listOf(7, 14, 28)
    private const val MIN_SNAPSHOTS_FOR_IMPROVEMENT = 3

    /**
     * Snapshots to store today: the pool balance at +7/+14/+28 days (tier 1 and full model) and
     * next week's per-category prediction (tier 1 and full).
     */
    fun snapshotsFor(result: ForecastResult): Pair<List<BalanceSnapshot>, List<CategoryWeekSnapshot>> {
        val balanceSnapshots = mutableListOf<BalanceSnapshot>()
        for (horizon in HORIZONS) {
            val targetDate = result.today.plusDays(horizon.toLong())
            val day = result.days.firstOrNull { it.date == targetDate } ?: continue
            balanceSnapshots += BalanceSnapshot(
                createdOn = result.today,
                targetDate = targetDate,
                tier = 1,
                p10 = null,
                p50 = day.tier1Balance,
                p90 = null,
            )
            balanceSnapshots += BalanceSnapshot(
                createdOn = result.today,
                targetDate = targetDate,
                tier = 3,
                p10 = day.p10,
                p50 = day.p50 ?: day.expectedBalance,
                p90 = day.p90,
            )
        }

        val categorySnapshots = mutableListOf<CategoryWeekSnapshot>()
        val nextWeekStart = mondayOnOrBefore(result.today).plusDays(7)
        val nextWeekDay = result.days.firstOrNull { it.date == nextWeekStart }
        if (nextWeekDay != null) {
            val categoryIds = (result.tier1WeeklyByCategory.keys + result.categoryEstimates.map { it.categoryId })
                .toSortedSet()
            for (categoryId in categoryIds) {
                val tier1 = result.tier1WeeklyByCategory[categoryId] ?: Money.ZERO
                val full = result.categoryEstimates
                    .firstOrNull { it.categoryId == categoryId && it.regime == nextWeekDay.regime }
                    ?.weeklyAmount ?: Money.ZERO
                categorySnapshots += CategoryWeekSnapshot(
                    createdOn = result.today,
                    weekStart = nextWeekStart,
                    categoryId = categoryId,
                    tier = 1,
                    predicted = tier1,
                )
                categorySnapshots += CategoryWeekSnapshot(
                    createdOn = result.today,
                    weekStart = nextWeekStart,
                    categoryId = categoryId,
                    tier = 3,
                    predicted = full,
                )
            }
        }

        return balanceSnapshots to categorySnapshots
    }

    /**
     * Only snapshots whose target has actually passed ([BalanceSnapshot.targetDate] before
     * [today], or [CategoryWeekSnapshot]'s week fully ended) are scored. Actual pool balance
     * uses the *current* pool membership.
     */
    fun evaluate(
        balanceSnapshots: List<BalanceSnapshot>,
        categorySnapshots: List<CategoryWeekSnapshot>,
        accounts: List<Account>,
        transactions: List<Transaction>,
        today: LocalDate,
    ): AccuracyReport {
        val poolAccountIds = accounts.filter { it.includeInSafeToSpend && !it.archived }.map { it.id }.toSet()

        val horizons = HORIZONS.map { horizon ->
            val due = balanceSnapshots.filter {
                it.targetDate.isBefore(today) &&
                    java.time.temporal.ChronoUnit.DAYS.between(it.createdOn, it.targetDate) == horizon.toLong()
            }
            val tier1 = due.filter { it.tier == 1 }
            val full = due.filter { it.tier == 3 }
            val errorsTier1 = tier1.map { snap ->
                abs(poolBalance(poolAccountIds, transactions, snap.targetDate).pence - snap.p50.pence)
            }
            val errorsFull = full.map { snap ->
                abs(poolBalance(poolAccountIds, transactions, snap.targetDate).pence - snap.p50.pence)
            }
            val withBand = full.filter { it.p10 != null && it.p90 != null }
            val coverage = if (withBand.isEmpty()) null else withBand.count { snap ->
                val actual = poolBalance(poolAccountIds, transactions, snap.targetDate)
                actual >= snap.p10!! && actual <= snap.p90!!
            }.toDouble() / withBand.size

            HorizonAccuracy(
                days = horizon,
                evaluated = full.size,
                meanAbsErrorTier1 = Money(errorsTier1.mean()),
                meanAbsErrorFull = Money(errorsFull.mean()),
                coverage = coverage,
            )
        }

        val evaluatedCategorySnaps = categorySnapshots.filter { it.weekStart.plusDays(6).isBefore(today) }
        val categories = evaluatedCategorySnaps.map { it.categoryId }.toSortedSet().map { categoryId ->
            val weeks = evaluatedCategorySnaps.filter { it.categoryId == categoryId }
            val tier1 = weeks.filter { it.tier == 1 }
            val full = weeks.filter { it.tier == 3 }
            val weekStarts = (tier1.map { it.weekStart } + full.map { it.weekStart }).toSet()
            val errorsTier1 = tier1.map { snap ->
                abs(actualCategoryWeek(categoryId, poolAccountIds, transactions, snap.weekStart).pence - snap.predicted.pence)
            }
            val errorsFull = full.map { snap ->
                abs(actualCategoryWeek(categoryId, poolAccountIds, transactions, snap.weekStart).pence - snap.predicted.pence)
            }
            CategoryAccuracy(
                categoryId = categoryId,
                evaluated = weekStarts.size,
                meanAbsErrorTier1 = Money(errorsTier1.mean()),
                meanAbsErrorFull = Money(errorsFull.mean()),
            )
        }

        val allFullSnapshots = balanceSnapshots.filter {
            it.tier == 3 && it.targetDate.isBefore(today) &&
                java.time.temporal.ChronoUnit.DAYS.between(it.createdOn, it.targetDate) in HORIZONS.map(Int::toLong)
        }
        val improvementPercent = if (allFullSnapshots.size < MIN_SNAPSHOTS_FOR_IMPROVEMENT) {
            null
        } else {
            val tier1Mean = horizons.flatMap { h ->
                balanceSnapshots.filter {
                    it.tier == 1 && it.targetDate.isBefore(today) &&
                        java.time.temporal.ChronoUnit.DAYS.between(it.createdOn, it.targetDate) == h.days.toLong()
                }.map { abs(poolBalance(poolAccountIds, transactions, it.targetDate).pence - it.p50.pence) }
            }
            val fullMean = horizons.flatMap { h ->
                balanceSnapshots.filter {
                    it.tier == 3 && it.targetDate.isBefore(today) &&
                        java.time.temporal.ChronoUnit.DAYS.between(it.createdOn, it.targetDate) == h.days.toLong()
                }.map { abs(poolBalance(poolAccountIds, transactions, it.targetDate).pence - it.p50.pence) }
            }
            val t1 = tier1Mean.mean()
            val fu = fullMean.mean()
            if (t1 <= 0L) null else (t1 - fu).toDouble() / t1.toDouble() * 100.0
        }

        return AccuracyReport(horizons, categories, improvementPercent)
    }

    private fun List<Long>.mean(): Long = if (isEmpty()) 0L else sum() / size

    private fun poolBalance(poolAccountIds: Set<Long>, transactions: List<Transaction>, asOf: LocalDate): Money {
        var total = 0L
        for (tx in transactions) {
            if (tx.accountId in poolAccountIds && !tx.date.isAfter(asOf)) total += tx.amount.pence
        }
        return Money(total)
    }

    private fun actualCategoryWeek(
        categoryId: Long,
        poolAccountIds: Set<Long>,
        transactions: List<Transaction>,
        weekStart: LocalDate,
    ): Money {
        val weekEnd = weekStart.plusDays(6)
        var total = 0L
        for (tx in transactions) {
            if (tx.kind != TxKind.NORMAL && tx.kind != TxKind.RECONCILIATION) continue
            if (tx.categoryId != categoryId) continue
            if (tx.accountId !in poolAccountIds) continue
            if (tx.date.isBefore(weekStart) || tx.date.isAfter(weekEnd)) continue
            total += tx.amount.pence
        }
        return Money(total)
    }

    private fun mondayOnOrBefore(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())
}
