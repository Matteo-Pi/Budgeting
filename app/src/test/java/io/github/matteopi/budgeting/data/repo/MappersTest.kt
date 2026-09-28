package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Entity <-> core model round trips for every table in docs/PLAN.md §6.1. */
class MappersTest {
    @Test
    fun account() {
        val model = Account(
            id = 1,
            name = "Santander current",
            type = AccountType.CURRENT,
            overdraftLimit = Money.pounds(250),
            includeInSafeToSpend = true,
            archived = false,
            sortOrder = 2,
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun category() {
        val model = Category(
            id = 7,
            name = "Groceries",
            flow = Flow.OUT,
            forecastMode = ForecastMode.LEARNED,
            colorArgb = 0xFF2E7D32,
            isSystem = false,
            systemKey = null,
            isMajorIncomeDefault = false,
            archived = false,
            sortOrder = 0,
        )
        assertEquals(model, model.toEntity().toModel())

        val system = model.copy(id = 99, isSystem = true, systemKey = "untracked", colorArgb = null)
        assertEquals(system, system.toEntity().toModel())
    }

    @Test
    fun transaction() {
        val model = Transaction(
            id = 3,
            accountId = 1,
            amount = Money(-320),
            date = LocalDate.of(2026, 9, 28),
            createdAtEpochMillis = 1_700_000_000_000,
            categoryId = 7,
            label = "Tesco",
            note = "Weekly shop",
            kind = TxKind.NORMAL,
            transferGroupId = null,
            recurringRuleId = null,
            scheduledItemId = null,
            importBatchId = null,
            importKey = null,
        )
        assertEquals(model, model.toEntity().toModel())

        val transfer = model.copy(
            id = 4,
            categoryId = null,
            kind = TxKind.TRANSFER,
            transferGroupId = "group-1",
            recurringRuleId = 5,
        )
        assertEquals(transfer, transfer.toEntity().toModel())
    }

    @Test
    fun recurringRule() {
        val model = RecurringRule(
            id = 2,
            label = "Rent",
            accountId = 1,
            categoryId = 8,
            amount = Money(-45000),
            frequency = Frequency.MONTHLY,
            interval = 1,
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2027, 6, 30),
            postingMode = PostingMode.AUTO,
            postedThrough = LocalDate.of(2026, 9, 1),
            toAccountId = null,
            isMajorIncome = false,
            active = true,
        )
        assertEquals(model, model.toEntity().toModel())

        val transfer = model.copy(id = 9, toAccountId = 2, categoryId = null, amount = Money(10000))
        assertEquals(transfer, transfer.toEntity().toModel())
    }

    @Test
    fun scheduledItem() {
        val model = ScheduledItem(
            id = 5,
            label = "Student loan",
            accountId = 1,
            categoryId = 11,
            amount = Money(310000),
            date = LocalDate.of(2026, 10, 12),
            postingMode = PostingMode.CONFIRM,
            toAccountId = null,
            isMajorIncome = true,
            posted = false,
            skipped = false,
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun termPeriod() {
        val model = TermPeriod(
            id = 1,
            name = "Autumn",
            startDate = LocalDate.of(2026, 9, 28),
            endDate = LocalDate.of(2026, 12, 12),
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun prediction() {
        val model = Prediction(
            id = 1,
            categoryId = 7,
            regime = Regime.TERM,
            mode = PredictionMode.ADJUST_PERCENT,
            weeklyAmount = Money.pounds(35),
            adjustPercent = -20,
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun sinkingFund() {
        val model = SinkingFund(
            id = 1,
            name = "Interrailing",
            target = Money.pounds(600),
            startDate = LocalDate.of(2026, 9, 1),
            targetDate = LocalDate.of(2027, 7, 1),
            categoryId = 5,
            status = SinkingFundStatus.ACTIVE,
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun scenario() {
        // ScenarioEntity.toModel() never carries items (that's a repository-level join), so only
        // id/name round-trip through the mapper; createdAt is an app/Room-only column.
        val model = Scenario(id = 4, name = "No takeaways")
        assertEquals(model, model.toEntity(createdAt = 1_700_000_000_000).toModel())
    }

    @Test
    fun scenarioItem() {
        val model = ScenarioItem(
            id = 1,
            scenarioId = 4,
            type = ScenarioItemType.CATEGORY_ADJUST,
            label = "Cut eating out",
            accountId = null,
            categoryId = 2,
            amount = Money.ZERO,
            date = null,
            frequency = null,
            interval = 1,
            endDate = null,
            percent = -30,
            regime = Regime.TERM,
        )
        assertEquals(model, model.toEntity().toModel())

        val oneOff = model.copy(
            id = 2,
            type = ScenarioItemType.ONE_OFF,
            accountId = 1,
            amount = Money(-5000),
            date = LocalDate.of(2026, 10, 1),
            percent = 0,
            regime = null,
        )
        assertEquals(oneOff, oneOff.toEntity().toModel())
    }

    @Test
    fun preset() {
        val model = Preset(
            id = 1,
            label = "Meal deal",
            amount = Money(385),
            flow = Flow.OUT,
            categoryId = 1,
            accountId = null,
            showOnWidget = true,
            sortOrder = 0,
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun merchantRule() {
        val model = MerchantRule(id = 1, merchantKey = "TESCO", categoryId = 1, label = "Tesco")
        assertEquals(model, model.toEntity().toModel())

        val noLabel = model.copy(id = 2, label = null)
        assertEquals(noLabel, noLabel.toEntity().toModel())
    }

    @Test
    fun reconciliation() {
        val model = Reconciliation(
            id = 1,
            accountId = 1,
            date = LocalDate.of(2026, 9, 28),
            expected = Money.pounds(100),
            actual = Money.pounds(92),
            transactionId = 42,
            periodStart = LocalDate.of(2026, 9, 21),
        )
        assertEquals(model, model.toEntity().toModel())

        val noTransaction = model.copy(id = 2, transactionId = null)
        assertEquals(noTransaction, noTransaction.toEntity().toModel())
    }

    @Test
    fun importBatch() {
        val model = ImportBatch(
            id = 1,
            accountId = 1,
            importedAtEpochMillis = 1_700_000_000_000,
            fileName = "statement.csv",
            rowCount = 42,
            firstDate = LocalDate.of(2026, 8, 1),
            lastDate = LocalDate.of(2026, 8, 31),
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun balanceSnapshot() {
        val model = BalanceSnapshot(
            id = 1,
            createdOn = LocalDate.of(2026, 9, 28),
            targetDate = LocalDate.of(2026, 10, 5),
            tier = 3,
            p10 = Money.pounds(40),
            p50 = Money.pounds(70),
            p90 = Money.pounds(110),
        )
        assertEquals(model, model.toEntity().toModel())

        val tier1 = model.copy(id = 2, tier = 1, p10 = null, p90 = null)
        assertEquals(tier1, tier1.toEntity().toModel())
    }

    @Test
    fun categoryWeekSnapshot() {
        val model = CategoryWeekSnapshot(
            id = 1,
            createdOn = LocalDate.of(2026, 9, 28),
            weekStart = LocalDate.of(2026, 9, 28),
            categoryId = 1,
            tier = 3,
            predicted = Money(-3500),
        )
        assertEquals(model, model.toEntity().toModel())
    }
}
