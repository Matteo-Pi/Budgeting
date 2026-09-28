package io.github.matteopi.budgeting.core.analysis

import io.github.matteopi.budgeting.core.forecast.CategoryEstimate
import io.github.matteopi.budgeting.core.forecast.DataSufficiency
import io.github.matteopi.budgeting.core.forecast.DayForecast
import io.github.matteopi.budgeting.core.forecast.EstimateSource
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccuracyTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 28).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
    private val account = Account(id = 1, name = "Current", type = AccountType.CURRENT)
    private val groceries = 1L

    private fun day(date: LocalDate, tier1: Long, expected: Long, p10: Long?, p50: Long?, p90: Long?) = DayForecast(
        date = date,
        regime = Regime.TERM,
        committedNet = Money.ZERO,
        expectedLearned = Money.ZERO,
        tier1Balance = Money.pounds(tier1),
        expectedBalance = Money.pounds(expected),
        p10 = p10?.let { Money.pounds(it) },
        p50 = p50?.let { Money.pounds(it) },
        p90 = p90?.let { Money.pounds(it) },
        reserved = Money.ZERO,
    )

    @Test
    fun `snapshotsFor creates a tier1 and a full model balance snapshot at each horizon`() {
        val result = testForecastResult(
            today,
            days = listOf(
                day(today.plusDays(7), tier1 = 100, expected = 105, p10 = 90, p50 = 106, p90 = 120),
                day(today.plusDays(14), tier1 = 200, expected = 210, p10 = 190, p50 = 211, p90 = 230),
                day(today.plusDays(28), tier1 = 300, expected = 310, p10 = 290, p50 = 311, p90 = 330),
            ),
        )
        val (balanceSnapshots, _) = Accuracy.snapshotsFor(result)

        assertEquals(6, balanceSnapshots.size)
        val at7Tier1 = balanceSnapshots.single { it.targetDate == today.plusDays(7) && it.tier == 1 }
        assertEquals(Money.pounds(100), at7Tier1.p50)
        assertNull(at7Tier1.p10)
        assertNull(at7Tier1.p90)
        assertEquals(today, at7Tier1.createdOn)

        val at7Full = balanceSnapshots.single { it.targetDate == today.plusDays(7) && it.tier == 3 }
        assertEquals(Money.pounds(106), at7Full.p50)
        assertEquals(Money.pounds(90), at7Full.p10)
        assertEquals(Money.pounds(120), at7Full.p90)
    }

    @Test
    fun `snapshotsFor falls back to expectedBalance as p50 when Monte Carlo was skipped`() {
        val result = testForecastResult(
            today,
            days = listOf(day(today.plusDays(7), tier1 = 100, expected = 105, p10 = null, p50 = null, p90 = null)),
        )
        val (balanceSnapshots, _) = Accuracy.snapshotsFor(result)
        val full = balanceSnapshots.single { it.tier == 3 }
        assertEquals(Money.pounds(105), full.p50)
        assertNull(full.p10)
    }

    @Test
    fun `snapshotsFor skips a horizon beyond the forecast`() {
        val result = testForecastResult(today, days = listOf(day(today.plusDays(7), 100, 105, 90, 106, 120)))
        val (balanceSnapshots, _) = Accuracy.snapshotsFor(result)
        assertEquals(2, balanceSnapshots.size) // only the +7 horizon has a matching day
    }

    @Test
    fun `snapshotsFor records next week's per-category prediction for tier1 and full`() {
        val nextWeekStart = today.plusDays(7)
        val result = testForecastResult(
            today,
            days = listOf(day(nextWeekStart, 100, 105, 90, 106, 120)),
            tier1WeeklyByCategory = mapOf(groceries to Money.pounds(-30)),
        ).copy(
            categoryEstimates = listOf(
                CategoryEstimate(
                    categoryId = groceries, regime = Regime.TERM, weeklyAmount = Money.pounds(-40),
                    automaticWeeklyAmount = Money.pounds(-40), source = EstimateSource.DATA, weeksOfData = 8,
                    sufficiency = DataSufficiency.TRUSTED, dayOfWeekShares = uniformDayShares,
                ),
            ),
        )

        val (_, categorySnapshots) = Accuracy.snapshotsFor(result)
        assertEquals(2, categorySnapshots.size)
        val tier1 = categorySnapshots.single { it.tier == 1 }
        val full = categorySnapshots.single { it.tier == 3 }
        assertEquals(nextWeekStart, tier1.weekStart)
        assertEquals(Money.pounds(-30), tier1.predicted)
        assertEquals(Money.pounds(-40), full.predicted)
    }

    @Test
    fun `evaluate scores tier1 vs full model error and full model coverage`() {
        val createdOn = today.minusDays(10)
        val targetDate = createdOn.plusDays(7) // in the past relative to `today`
        val transactions = listOf(
            Transaction(accountId = account.id, amount = Money.pounds(115), date = LocalDate.of(2000, 1, 1), categoryId = null, kind = TxKind.OPENING_BALANCE),
        )
        val balanceSnapshots = listOf(
            BalanceSnapshot(createdOn = createdOn, targetDate = targetDate, tier = 1, p10 = null, p50 = Money.pounds(100), p90 = null),
            BalanceSnapshot(createdOn = createdOn, targetDate = targetDate, tier = 3, p10 = Money.pounds(90), p50 = Money.pounds(110), p90 = Money.pounds(130)),
        )

        val report = Accuracy.evaluate(balanceSnapshots, emptyList(), listOf(account), transactions, today)
        val horizon7 = report.horizons.single { it.days == 7 }
        assertEquals(1, horizon7.evaluated)
        assertEquals(Money.pounds(15), horizon7.meanAbsErrorTier1) // |115-100|
        assertEquals(Money.pounds(5), horizon7.meanAbsErrorFull) // |115-110|
        assertEquals(1.0, horizon7.coverage)
    }

    @Test
    fun `evaluate ignores a snapshot whose target date has not yet arrived`() {
        val createdOn = today.minusDays(3)
        val targetDate = today.plusDays(4) // in the future
        val balanceSnapshots = listOf(
            BalanceSnapshot(createdOn = createdOn, targetDate = targetDate, tier = 1, p10 = null, p50 = Money.pounds(100), p90 = null),
            BalanceSnapshot(createdOn = createdOn, targetDate = targetDate, tier = 3, p10 = Money.pounds(90), p50 = Money.pounds(100), p90 = Money.pounds(110)),
        )
        val report = Accuracy.evaluate(balanceSnapshots, emptyList(), listOf(account), emptyList(), today)
        val horizon7 = report.horizons.single { it.days == 7 }
        assertEquals(0, horizon7.evaluated)
    }

    @Test
    fun `evaluate scores week-ahead category error once the week has ended`() {
        val weekStart = today.minusDays(21)
        val transactions = listOf(
            Transaction(accountId = account.id, amount = Money.pounds(-45), date = weekStart.plusDays(1), categoryId = groceries),
        )
        val categorySnapshots = listOf(
            CategoryWeekSnapshot(createdOn = weekStart.minusDays(7), weekStart = weekStart, categoryId = groceries, tier = 1, predicted = Money.pounds(-30)),
            CategoryWeekSnapshot(createdOn = weekStart.minusDays(7), weekStart = weekStart, categoryId = groceries, tier = 3, predicted = Money.pounds(-40)),
        )
        val report = Accuracy.evaluate(emptyList(), categorySnapshots, listOf(account), transactions, today)

        val cat = report.categories.single { it.categoryId == groceries }
        assertEquals(1, cat.evaluated)
        assertEquals(Money.pounds(15), cat.meanAbsErrorTier1) // |-45 - -30| = 15
        assertEquals(Money.pounds(5), cat.meanAbsErrorFull) // |-45 - -40| = 5
    }

    @Test
    fun `evaluate ignores a category snapshot whose week has not fully ended`() {
        val weekStart = today.minusDays(3) // week is still in progress
        val categorySnapshots = listOf(
            CategoryWeekSnapshot(createdOn = weekStart.minusDays(7), weekStart = weekStart, categoryId = groceries, tier = 1, predicted = Money.pounds(-30)),
        )
        val report = Accuracy.evaluate(emptyList(), categorySnapshots, listOf(account), emptyList(), today)
        assertTrue(report.categories.isEmpty())
    }

    private fun dueSnapshotPair(offset: Long, actual: Long, tier1P50: Long, fullP50: Long): List<BalanceSnapshot> {
        val createdOn = today.minusDays(30 + offset)
        val targetDate = createdOn.plusDays(7)
        return listOf(
            BalanceSnapshot(createdOn = createdOn, targetDate = targetDate, tier = 1, p10 = null, p50 = Money.pounds(tier1P50), p90 = null),
            BalanceSnapshot(createdOn = createdOn, targetDate = targetDate, tier = 3, p10 = Money.pounds(fullP50 - 20), p50 = Money.pounds(fullP50), p90 = Money.pounds(fullP50 + 20)),
        )
    }

    @Test
    fun `improvementPercent is null with fewer than three evaluated full-model snapshots`() {
        val transactions = listOf(
            Transaction(accountId = account.id, amount = Money.pounds(100), date = LocalDate.of(2000, 1, 1), categoryId = null, kind = TxKind.OPENING_BALANCE),
        )
        val snapshots = dueSnapshotPair(0, actual = 100, tier1P50 = 80, fullP50 = 95) +
            dueSnapshotPair(1, actual = 100, tier1P50 = 80, fullP50 = 95)
        val report = Accuracy.evaluate(snapshots, emptyList(), listOf(account), transactions, today)
        assertNull(report.improvementPercent)
    }

    @Test
    fun `improvementPercent is positive when the full model beats tier1 across at least three snapshots`() {
        val transactions = listOf(
            Transaction(accountId = account.id, amount = Money.pounds(100), date = LocalDate.of(2000, 1, 1), categoryId = null, kind = TxKind.OPENING_BALANCE),
        )
        // Actual is always 100; tier1 is off by 20, full model off by 5, every time -> full model clearly better.
        val snapshots = (0 until 3).flatMap { dueSnapshotPair(it.toLong(), actual = 100, tier1P50 = 80, fullP50 = 95) }
        val report = Accuracy.evaluate(snapshots, emptyList(), listOf(account), transactions, today)

        val improvement = assertNotNull(report.improvementPercent)
        assertTrue(improvement > 0.0, "expected a positive improvement, got $improvement")
    }
}
